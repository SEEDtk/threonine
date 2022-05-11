/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.experiments.ExcelUtils;
import org.theseed.io.TabbedLineReader;
import org.theseed.samples.SampleId;
import org.theseed.samples.SugarUsage;
import org.theseed.samples.WellDescriptor;
import org.theseed.utils.BaseReportProcessor;
import org.theseed.utils.ParseFailureException;

/**
 * This class produces a report on glucose utilization from an existing master file and a set of
 * result spreadsheets.
 *
 * Glucose utilization experiments are produced from plates identical to those used to compute
 * threonine production.  Each spreadsheet is associated with a plate from a previous threonine-
 * production experiment.  The sample of interest is computed by finding the appropriate plate
 * and well on the master sample list.  The output will contain the normalized sample ID, the
 * glucose usage, the plate origin, and the yield.  This information can be used later to update
 * the big production table.
 *
 * Note that the master sample list is output by ThrFixProcessor.
 *
 * The positional parameters are the name of the input directory and the name of the master sample file.
 *
 * The input directory must contain a tab-delimited file "map.txt" that describes the input spreadsheets.
 * The file has two columns-- "plate" which contains a plate ID, "time" which contains a time point
 * number, and "file" which contains the name of the Excel file containing the experiment results.
 * The excel file contains the results in a plate grid, with numbers assigned to the columns and letters
 * A-H to the rows.  The grid of interest is near the bottom of the sheet and is distinguished by a marker
 * string ending with " g/L".
 *
 * The usage is computed by subtracting the cell value from the base level amount.  If the cell value is
 * greater than the base level amount by less than 10%, it is flattened to zero.  If the cell value is
 * greater by more than that, it is flagged as suspect and flattened to zero.  Suspect values are flagged
 * in the output report.  The base level amount is computed by taking the mean of the values for column
 * 11 of the plate.
 *
 * The map file will be read first.  For each well, a well ID will be assigned consisting of the plate ID,
 * the well address, and the time point.  These will be mapped to the sugar usage.  The master sample file will
 * be read next to get the sample IDs and the threonine production levels and combine these with the
 * sugar usage to produce the output report.
 *
 * The command-line options are as follows:
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 * -o	report output file (if not STDOUT)
 * -e	error factor; high numbers less than this times the base amount are flagged as suspect; the
 * 		default is 1.1, indicating that a 10% overage is acceptable
 *
 * --minSugar	minimum useful sugar level
 * --minThr		minimum useful threonine level
 *
 * @author Bruce Parrello
 *
 */
public class SugarUtilizationProcessor extends BaseReportProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(SugarUtilizationProcessor.class);
    /** map file name */
    private File mapFile;
    /** medium ID to use */
    private static final String MEDIUM = "M1";
    /** threonine grams/mole */
    private static final double THR_G_PER_MOLE = 119.1192;
    /** sugar grams/mole */
    private static final double SUGAR_G_PER_MOLE = 180.156;

    // COMMAND-LINE OPTIONS

    /** acceptable error factor */
    @Option(name = "--error", aliases = { "-e" }, metaVar = "1.2", usage = "acceptable error factor, as a fraction above 1")
    private double errorFactor;

    /** minimum useful threonine level */
    @Option(name = "--minThr", metaVar = "0.01", usage = "minimum useful threonine value")
    private double minThreonine;

    /** minimum useful sugar usage */
    @Option(name = "--minSugar", metaVar = "0.1", usage = "minimum useful sugar usage")
    private double minSugar;

    /** input directory */
    @Argument(index = 0, metaVar = "inDir", usage = "input directory for map file and spreadsheets", required = true)
    private File inDir;

    /** input master file */
    @Argument(index = 1, metaVar = "combined_master.tsv", usage = "master file containing threonine production",
            required = true)
    private File masterFile;

    @Override
    protected void setReporterDefaults() {
        this.errorFactor = 1.1;
        this.minSugar = 1.0;
        this.minThreonine = 0.2;
    }

    @Override
    protected void validateReporterParms() throws IOException, ParseFailureException {
        // Verify the numbers.
        if (this.errorFactor < 1.0)
            throw new ParseFailureException("Error factor must be >= 1.0.");
        if (this.minSugar <= 0.0)
            throw new ParseFailureException("Minimum sugar level must be positive.");
        if (this.minThreonine <= 0.0)
            throw new ParseFailureException("Minimum threonine level must be positive.");
        // Verify the input.
        if (! this.inDir.isDirectory())
            throw new FileNotFoundException("Input directory is not found or invalid.");
        // Check for the map file.
        this.mapFile = new File(this.inDir, "map.txt");
        if (! this.mapFile.canRead())
            throw new FileNotFoundException("Input directory does not contain a map file, or file is unreadable.");
        if (! this.masterFile.canRead())
            throw new FileNotFoundException("Input master file is not found or unreadable.");
    }

    @Override
    protected void runReporter(PrintWriter writer) throws Exception {
        // Build the well map from the spreadsheets.
        SugarUsage.setErrorLevel(this.errorFactor);
        Map<WellDescriptor, SugarUsage> wellMap = this.buildWellMap();
        // Now we read the master file and create the output.  Start with the output header.
        writer.println("sample_id\torigin\tproduction\tusage\tutilization\tmole_util\tyield\tmole_yield\tsuspect");
        // These will track our matches.
        int masterIn = 0;
        int wellFound = 0;
        int suspect = 0;
        // Open the master file input and get the column locations.
        log.info("Processing master file {}.", this.masterFile);
        try (TabbedLineReader inStream = new TabbedLineReader(this.masterFile)) {
            int strainCol = inStream.findField("strain_lower");
            int iptgCol = inStream.findField("iptg");
            int timeCol = inStream.findField("time");
            int thrCol = inStream.findField("Thr");
            int plateCol = inStream.findField("experiment");
            int wellCol = inStream.findField("Sample_y");
            // Loop through the master file.
            for (TabbedLineReader.Line line : inStream) {
                masterIn++;
                String plate = line.get(plateCol);
                String wellAddress = line.get(wellCol);
                double time = line.getDouble(timeCol);
                WellDescriptor well = new WellDescriptor(plate, wellAddress, time);
                SugarUsage usage = wellMap.get(well);
                if (usage != null) {
                    // Here we have a sample for which sugar data exists.  Get the identifying data for
                    // the actual sample itself.
                    boolean iptgFlag = line.getFancyFlag(iptgCol);
                    String strain = line.get(strainCol);
                    SampleId sample = SampleId.translate(strain, time, iptgFlag, MEDIUM);
                    if (sample == null)
                        throw new IOException("Invalid strain string \"" + strain + "\" in master file.");
                    else {
                        wellFound++;
                        // We have the sample ID and the sugar usage.  All that's left is the threonine
                        // production level.
                        double production = line.getDouble(thrCol);
                        String origin = well.getOrigin();
                        double usageLevel = usage.getUsage();
                        double utilization = 0.0;
                        double moleUsage = 0.0;
                        double yield = 0.0;
                        double moleYield = 0.0;
                        boolean useful = false;
                        if (production >= this.minThreonine && usageLevel >= this.minSugar) {
                            utilization = usageLevel / production;
                            moleUsage = utilization * (THR_G_PER_MOLE / SUGAR_G_PER_MOLE);
                            yield = production / usageLevel;
                            moleYield = yield * (SUGAR_G_PER_MOLE / THR_G_PER_MOLE);
                            useful = true;
                        } else if (production == 0.0 && usageLevel < this.minSugar)
                            useful = true;
                        String suspicious = "";
                        if (usage.isSuspicious()) {
                            suspect++;
                            suspicious = "Y";
                        } else if (! useful)
                            suspicious = "?";
                        writer.printf("%s\t%s\t%8.4f\t%8.4f\t%8.4f\t%8.4f\t%8.4f\t%8.4f\t%s%n", sample.toString(),
                                origin, production, usageLevel, utilization, moleUsage, yield,
                                moleYield, suspicious);
                    }
                }
            }
            log.info("{} master file records processed, {} were matched to wells, {} suspicious, {} wells available.",
                    masterIn, wellFound, suspect, wellMap.size());
        }
    }

    /**
     * @return the well map computed from the input directory
     *
     * @throws IOException
     */
    private Map<WellDescriptor, SugarUsage> buildWellMap() throws IOException {
        Map<WellDescriptor, SugarUsage> retVal = new HashMap<WellDescriptor, SugarUsage>(3000);
        // Open the map file.
        log.info("Reading plate definitions from {}.", this.mapFile);
        try (TabbedLineReader mapStream = new TabbedLineReader(this.mapFile)) {
            int plateCol = mapStream.findField("plate");
            int fileCol = mapStream.findField("file");
            int timeCol = mapStream.findField("time");
            // Loop throgh the plate records.
            for (TabbedLineReader.Line line : mapStream) {
                String plateId = line.get(plateCol);
                File plateFile = new File(this.inDir, line.get(fileCol));
                double timePoint = line.getDouble(timeCol);
                log.info("Reading data for {} at time point {} from {}.", plateId, timePoint, plateFile);
                this.processPlateSheet(retVal, plateId, timePoint, plateFile);
            }
        }
        return retVal;
    }

    /**
     * Read the plate information in a spreadsheet and store its sugar usage in the specified well map.
     *
     * @param wellMap		well map to contain sugar usage for each well
     * @param plateId		ID of the plate for the specified file
     * @param timePoint		time point for the specified file
     * @param plateFile		spreadsheet file containing the usage data
     *
     * @throws IOException
     */
    private void processPlateSheet(Map<WellDescriptor, SugarUsage> wellMap, String plateId, double timePoint, File plateFile)
            throws IOException {
        // Open the spreadsheet file and position on the first sheet.
        try (InputStream plateStream = new FileInputStream(plateFile);
                Workbook plateBook = new XSSFWorkbook(plateStream)) {
            Sheet plateSheet = plateBook.getSheetAt(0);
            // This value will contain the well letter for the next row.
            char rowLetter = 'A';
            // Loop through the sheet, finding the grid.
            boolean gridFound = false;
            int rowsChecked = 0;
            Iterator<Row> rowIter = plateSheet.rowIterator();
            while (rowIter.hasNext() && ! gridFound) {
                rowsChecked++;
                Cell cell0 = rowIter.next().getCell(0);
                gridFound = ExcelUtils.stringValue(cell0).endsWith(" g/L");
            }
            if (! gridFound)
                throw new IOException("No sugar "
                        + "grid found in " + plateFile);
            // Skip the title row.
            rowIter.next();
            // This will save the grid rows.  We need two passes-- one to compute the base level,
            // another for the actual computation.
            double baseLevel = 0.0;
            List<Row> gridRows = new ArrayList<Row>(8);
            for (int i = 0; i < 8; i++) {
                Row currentRow = rowIter.next();
                gridRows.add(currentRow);
                double level = ExcelUtils.numValue(currentRow.getCell(12));
                baseLevel += level;
                rowsChecked++;
            }
            baseLevel /= 8.0;
            SugarUsage.setLevels(baseLevel);
            // Now loop through the rows getting the real sugar data.
            for (Row currentRow : gridRows) {
                // Here we have a grid row.  Loop through the wells.
                for (int i = 1; i <= 12; i++) {
                    String wellAddress = String.format("%c%d", rowLetter, i);
                    WellDescriptor well = new WellDescriptor(plateId, wellAddress, timePoint);
                    Cell cellW = currentRow.getCell(i + 1);
                    double level = ExcelUtils.numValue(cellW);
                    if (level < 0.0) level = 0.0;
                    SugarUsage usage = new SugarUsage(level);
                    wellMap.put(well, usage);
                }
                // Set up for the next row.
                rowLetter++;
            }
            log.info("{} rows checked, base level {}.", rowsChecked, baseLevel);
        }
    }

}
