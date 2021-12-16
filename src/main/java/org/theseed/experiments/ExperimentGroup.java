/**
 *
 */
package org.theseed.experiments;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.io.LineReader;

/**
 * An experiment group is a directory containing one or more experiments.  Each experiment has an ID, a layout,
 * a growth assay, and production data.  Frequenty, a single production data run covers four experiments, due
 * to the differing plate sizes.  There are many experiment formats, and this class has subclasses to deal with
 * them.  We talk about a "small plate" as representing an experiment.  Each small plate has 96 wells, in rows
 * A through H and columns 1 through 12.  The production data is taken from large plates with 384 wells, in rows
 * A through P and columns 1 through 24.  An experiment is represented by a small plate.  Each experiment ID
 * consists of the ID for the group followed by the small plate's ID.  All of these IDs are normalized to upper
 * case.
 *
 * Certain files are invariant.
 *
 * The production spreadsheets each contain two grids of importance that describe a 384-well plate.  The first grid
 * is immediately below the word "Sample" in the first column.  Each data cell in this grid contains the content
 * of the well, described in a format determined by the experiment group type.  The second grid is immediately below
 * the word "mg/L" in the first column.  Each data cell in this grid contains the threonine production in mg/L.  These
 * values must be converted to g/L before output.
 *
 * The layout files have different formats according to the group type.
 *
 * The bad wells file is a flat file named "badWells.txt".  If it exists, each line consists of a plate name, a tab
 * and a comma-delimited list of wells on that plate that should be marked suspicious.
 *
 * Each small plate has its own growth data file.  The growth data file is comma-delimited and quoted.
 * The second line will contain the plate identification surrounded by spaces.  As a weird special case, a
 * plate ID of "NO PLASMID" is changed to "NONE".  Further on, the real data will appear after a header line
 * ("Well","Sample","OD(600)").  This section contains the well ID in the first column and the growth in the third.
 * Note that for each column, the growth value must be normalized by subtracting an offset and multiplying by the
 * dilution factor.
 *
 * @author Bruce Parrello
 *
 */
public abstract class ExperimentGroup extends ExcelUtils implements Iterable<ExperimentData> {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(ExperimentGroup.class);
    /** map of plate IDs to experiments */
    private Map<String, ExperimentData> experimentMap;
    /** experiment group ID */
    private String expID;
    /** normalization factor for optical density */
    private double normFactor;
    /** number of columns in a big plate */
    private int bigCols;
    /** default time point */
    private double timePoint;
    /** layout file names */
    private List<File> layoutFiles;
    /** production file names */
    private List<File> prodFiles;
    /** growth file names */
    private List<File> growthFiles;
    /** directory containing this group */
    private File inDir;
    /** bad-well sets */
    private Map<String, Set<String>> badWells;
    /** set of time points to create during layout */
    private Set<Double> timeSeries;
    /** start column (0-based) for spreadsheet tables */
    private int startCol;

    /** special NONE string */
    protected static final String NONE_STRING = "NO PLASMID";
    /** pattern for blank cells */
    protected static final Pattern BLANK_CELL = Pattern.compile("blank|blak", Pattern.CASE_INSENSITIVE);
    /** pattern for well labels */
    protected static final Pattern WELL_LABEL = Pattern.compile("[A-Z]\\d+");
    /** pattern for dilution */
    private static final Pattern DILUTION_LINE = Pattern.compile("(\\d+)[- ]fold\\s+dilution");

    /**
     * This is a simple utility class for returning the sample information from
     * a production file's cell map.
     */
    protected static class SampleDesc {

        private final String plate;
        private final String well;
        private final double time;

        /**
         * Create a sample description.
         *
         * @param plateId		ID of the relevant plate
         * @param wellId		label of the relevant well
         * @param timePoint		time point
         */
        public SampleDesc(String plateId, String wellId, double timePoint) {
            this.plate = plateId;
            this.well = wellId;
            this.time = timePoint;
        }

        /**
         * @return the plate ID
         */
        public String getPlate() {
            return this.plate;
        }

        /**
         * @return the well label
         */
        public String getWell() {
            return this.well;
        }

        /**
         * @return the time point
         */
        public double getTime() {
            return this.time;
        }

    }

    /**
     * Construct a new experiment group.
     *
     * @param dir		input directory
     * @param id		experiment ID
     *
     * @throws IOException
     */
    public ExperimentGroup(File dir, String id) throws IOException {
        super();
        this.inDir = dir;
        this.expID = id;
        this.normFactor = 0.04;
        this.bigCols = 24;
        this.timePoint = 24.0;
        this.startCol = 0;
        // Initialize the maps.
        this.badWells = new HashMap<String, Set<String>>();
        this.experimentMap = new HashMap<String, ExperimentData>();
        // Parse the file list.
        // Parse out the files in the input directory.
        log.info("Analyzing files in input directory {}.", this.inDir);
        this.growthFiles = new ArrayList<File>(10);
        this.prodFiles = new ArrayList<File>(5);
        this.layoutFiles = new ArrayList<File>(10);
        for (File inFile : this.inDir.listFiles()) {
            String name = inFile.getName();
            // A suffix of ".csv" indicates a growth file.
            if (StringUtils.endsWith(name, ".csv"))
                this.growthFiles.add(inFile);
            else if (this.isLayoutFile(name))
                this.layoutFiles.add(inFile);
            else if (StringUtils.endsWith(name, ".xlsx"))
                this.prodFiles.add(inFile);
            else if (name.contentEquals("badWells.txt")) {
                // Here we have a bad-well list file.
                this.readBadWellFile(inFile);
            }
        }
        // Compute the time series from the production file names.
        this.timeSeries = new TreeSet<Double>();
        for (File prodFile : this.prodFiles) {
            double time = this.computeTimePoint(prodFile);
            this.timeSeries.add(time);
        }
        // If the time series is empty, use the default.
        if (this.timeSeries.isEmpty())
            this.timeSeries.add(this.timePoint);
        log.info("{} time points will be used for this run.", this.timeSeries.size());
    }

    /**
     * @return TRUE if the specified name indicates a layout file, else FALSE
     *
     * @param name	file name to check
     */
    protected abstract boolean isLayoutFile(String name);

    /**
     * Read the bad-well information from the specified file.
     *
     * @param inFile	input file with bad well information
     *
     * @throws IOException
     */
    private void readBadWellFile(File inFile) throws IOException {
        int count = 0;
        try (LineReader inStream = new LineReader(inFile)) {
            log.info("Reading bad-well data from \"{}\".", inFile);
            for (String line : inStream) {
                String[] parts = StringUtils.split(line, '\t');
                Set<String> wells = Arrays.stream(StringUtils.split(parts[1], ',')).collect(Collectors.toSet());
                this.badWells.put(parts[0], wells);
                count += wells.size();
            }
            log.info("{} bad wells found in {} plates.", count, this.badWells.size());
        }
    }

    /**
     * Read the growth data for a plate from a growth file.
     *
     * @param growthFile	input growth file to read
     * @param time			the time point of this growth
     *
     * @throws IOException
     */
    protected void readGrowthFile(File growthFile, double time) throws IOException {
        try (Reader reader = new FileReader(growthFile)) {
            Iterator<CSVRecord> records = CSVFormat.EXCEL.parse(reader).iterator();
            // Verify that this is a growth file.
            CSVRecord record = records.next();
            String recordLabel = record.get(0);
            if (! recordLabel.startsWith("New assay") && ! recordLabel.startsWith("OD"))
                throw new IOException("\"" + growthFile + " does not appear to be a valid growth file.");
            log.info("Reading growth information from \"{}\".", growthFile);
            // Now compute the plate ID.
            String marker = records.next().get(0).toUpperCase();
            String plate = null;
            if (marker.contains(NONE_STRING))
                plate = "NONE";
            else {
                String[] parts = StringUtils.split(marker);
                for (String part : parts) {
                    if (this.experimentMap.containsKey(part))
                        plate = part;
                }
            }
            if (plate == null)
                throw new IOException("Could not find plate ID in file " + growthFile + ".");
            ExperimentData results = this.experimentMap.get(plate);
            if (results == null)
                throw new IOException("Could not find experiment plate " + plate + ".");
            // Look for the dilution factor.
            double factor = 0.0;
            while (records.hasNext() && factor == 0.0) {
                marker = records.next().get(0).toLowerCase();
                Matcher m = DILUTION_LINE.matcher(marker);
                if (m.matches())
                    factor = Double.valueOf(m.group(1));
                if (marker.contentEquals("results by well")) {
                    // No dilution line.  Stop here and set the factor to 1.0.
                    factor = 10.0;
                }
            }
            // Now find the well growths.
            int storeCount = 0;
            int skipCount = 0;
            while (records.hasNext()) {
                record = records.next();
                String label = record.get(0);
                if (WELL_LABEL.matcher(label).matches()) {
                    double value = Double.valueOf(record.get(2));
                    // Here we have an actual growth value.
                    ExperimentData.Result result = results.getResult(label, time);
                    if (result != null) {
                        result.setGrowth((value - this.normFactor) * factor);
                        storeCount++;
                    } else {
                        skipCount++;
                    }
                }
            }
            log.info("{} values stored, {} values skipped for plate {}.", storeCount, skipCount, plate);
        }
    }

    /**
     * Process all the files in this experiment group.  This method insures everything happens in
     * the correct order-- layout first, then growth, then production.
     *
     * @throws IOException
     */
    public void processFiles() throws IOException {
        for (File layoutFile : this.layoutFiles) {
            log.info("Analyzing layout file \"{}\".", layoutFile);
            this.readLayoutFile(layoutFile);
        }
        for (File growthFile : this.growthFiles) {
            log.info("Analyzing growth file \"{}\".", growthFile);
            double time = this.computeTimePoint(growthFile);
            this.readGrowthFile(growthFile, time);
        }
        for (File prodFile : this.prodFiles) {
            log.info("Analyzing production spreadsheet \"{}\".", prodFile);
            double time = this.computeTimePoint(prodFile);
            this.readProductionFile(prodFile, time);
        }
    }

    /**
     *
     * @return the time point to use for this file
     *
     * @param growthFile	file name
     *
     */
    protected abstract double computeTimePoint(File growthFile);

    /**
     * Process the specified layout file to store the plate layouts in the experiment map.
     *
     * @param layoutFile	layout file to read
     *
     * @throws IOException
     */
    protected abstract void readLayoutFile(File layoutFile) throws IOException;

    /**
     * Read the production data from the specified production spreadsheet.  The production spreadsheet contains
     * two tables of interest-- one that describes the content of each cell (plate and
     *
     * @param prodFile	file name of the spreadsheet
     * @param time		time point for this file
     *
     * @throws IOException
     */
    protected void readProductionFile(File prodFile, double time) throws IOException {
        try (InputStream fileStream = new FileInputStream(prodFile);
                Workbook workbook = new XSSFWorkbook(fileStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            log.info("Processing production sheet in \"{}\".", prodFile);
            // This maps each row to the list of cells in the row.
            Map<String, ExperimentData.Result[]> cellMaps = new HashMap<String, ExperimentData.Result[]>(16);
            // Look for the mapping sheet.
            Iterator<Row> rowIter = sheet.rowIterator();
            if (! findMarker(rowIter, "Sample"))
                throw new IOException("Could not find Sample section in \"" + prodFile.toString() + "\".");
            // Skip the header line of the mapping sheet.
            rowIter.next();
            boolean done = false;
            int valueCount = 0;
            while (rowIter.hasNext() && ! done) {
                Row row = rowIter.next();
                String label = stringValue(row.getCell(this.startCol));
                if (label.length() != 1)
                    done = true;
                else {
                    // Here we have a plate row description.  Process each of the columns.
                    ExperimentData.Result[] results = new ExperimentData.Result[this.bigCols];
                    for (int c = 0 ; c < this.bigCols; c++) {
                        // Get the cell content description.  Note we fix embedded new-lines.
                        Cell cell = row.getCell(c + this.startCol);
                        String data = StringUtils.replaceChars(stringValue(cell), "\n", " ").toUpperCase();
                        // Parse it to compute the plate, label, and time point.
                        SampleDesc sample = this.parseSampleName(data);
                        if (sample == null)
                            results[c] = null;
                        else {
                            String plate = sample.getPlate();
                            String well = sample.getWell();
                            if (plate.toLowerCase().contentEquals("nopl"))
                                plate = "NONE";
                            ExperimentData experiment = this.experimentMap.get(plate);
                            if (experiment == null)
                                log.warn("Invalid plate ID \"{}\" in sample map.", plate);
                            else {
                                // Get the well's result object.
                                ExperimentData.Result result = experiment.getResult(well, time);
                                results[c] = result;
                                if (result != null) {
                                    // Check for a bad well.
                                    Set<String> badWellSet = this.badWells.get(plate);
                                    result.setSuspect(badWellSet != null && badWellSet.contains(well));
                                    valueCount++;
                                }
                            }
                        }
                    }
                    // Store the results for this row label.
                    cellMaps.put(label, results);
                }
            }
            log.info("{} well mappings found.", valueCount);
            // Now we have the well mappings.  Skip ahead to the production table.
            if (! findMarker(rowIter, "mg/L"))
                throw new IOException("Could not find Production section in \"" + prodFile.toString() + "\".");
            // Skip the header line of the production table.
            rowIter.next();
            done = false;
            valueCount = 0;
            // Loop through the label rows.
            while (rowIter.hasNext() && ! done) {
                Row row = rowIter.next();
                String label = stringValue(row.getCell(startCol));
                if (label.length() != 1)
                    done = true;
                else {
                    // Here we have a plate row description.  Process each of the columns.
                    ExperimentData.Result[] results = cellMaps.get(label);
                    for (int c = 0; c < this.bigCols; c++) {
                        if (results[c] != null) {
                            Cell cell = row.getCell(c + this.startCol);
                            results[c].setProduction(numValue(cell) / 1000.0);
                            valueCount++;
                        }
                    }
                }
            }
            log.info("{} production values stored.", valueCount);
        }
    }

    /**
     * Parse the sample name from the production file cell map.
     *
     * @param data	content of the cell map, normalized to upper case with new-lines fixed
     *
     * @return a sample description, or NULL if the cell does not contain a mapping entry
     */
    protected abstract SampleDesc parseSampleName(String data);

    /**
     * @return the optical density normalization factor
     */
    public double getNormFactor() {
        return this.normFactor;
    }

    /**
     * Specify a new optical density normalization factor.
     *
     * @param normFactor 	the factor to set
     */
    public void setNormFactor(double normFactor) {
        this.normFactor = normFactor;
    }

    /**
     * @return the number of columns in a big plate
     */
    public int getBigCols() {
        return this.bigCols;
    }

    /**
     * Specify the number of columns in a big plate.
     *
     * @param bigCols 	the number of columns to set
     */
    public void setBigCols(int bigCols) {
        this.bigCols = bigCols;
    }

    /**
     * @return the default timePoint
     */
    public double getTimePoint() {
        return this.timePoint;
    }

    /**
     * Specify the default time point.
     *
     * @param timePoint 	the time point to set
     */
    public void setTimePoint(double timePoint) {
        this.timePoint = timePoint;
    }

    /**
     * @return the expID
     */
    public String getExpID() {
        return this.expID;
    }

    @Override
    public Iterator<ExperimentData> iterator() {
        return this.experimentMap.values().iterator();
    }

    /**
     * Create an experiment for the specified plate.
     *
     * @param plate
     */
    public void createExperiment(String plate) {
        ExperimentData experiment = new ExperimentData(this.expID + plate);
        this.experimentMap.put(plate, experiment);
    }

    /**
     * Create results for the specified well on the specified plate
     *
     * @param plate		target plate
     * @param strain	strain in the specified well
     * @param well		well label
     * @param iptgFlag	TRUE if IPTG is in effect, else FALSE
     */
    public void store(String plate, String strain, String well, boolean iptgFlag) {
        ExperimentData results = this.experimentMap.get(plate);
        for (Double time : this.timeSeries) {
            boolean iptgMode = (iptgFlag && time >= 5.0);
            results.store(strain, well, time, iptgMode);
        }
    }

    /**
     * @return the result descriptor for the specified plate and well.
     *
     * @param plate		plate containing the result
     * @param well		well containing the result
     * @param time		time point for the result
     */
    public ExperimentData.Result getResult(String plate, String well, double time) {
        ExperimentData.Result retVal = null;
        ExperimentData experiment = this.experimentMap.get(plate);
        if (experiment != null)
            retVal = experiment.getResult(well, time);
        return retVal;
    }

    /**
     * @param startCol 	the new starting column for spreadsheet samples
     */
    public void setStartCol(int startCol) {
        this.startCol = startCol;
    }

}
