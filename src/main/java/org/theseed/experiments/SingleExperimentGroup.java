/**
 *
 */
package org.theseed.experiments;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.commons.lang3.StringUtils;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.excel.ExcelUtils;

/**
 * This type of group involves a single plate layout with multiple time points.  All data is stored in Excel spreadsheets.
 * The growth data spreadsheets contain the densities for a single time point.  The layout is in a spreadsheet with the
 * well ID in the first column and the new-format sample ID in the second.  The threonine production data is in a standard
 * production spreadsheet.
 *
 * @author Bruce Parrello
 *
 */
public class SingleExperimentGroup extends ExperimentGroup {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(SingleExperimentGroup.class);
    /** saved plate ID */
    private String plateId;
    /** file name pattern for a growth file */
    private static final Pattern GROWTH_FILE_NAME = Pattern.compile("96well\\s+(\\S+)\\s+(\\d+|\\d+p\\d+|seed)(?:\\s*hrs?.+)?\\.xlsx");
    /** file name pattern for a layout file */
    private static final Pattern LAYOUT_FILE_NAME = Pattern.compile("(\\S+)\\s+layout\\s+\\d+\\.xlsx");
    /** sample ID pattern for the production file */
    private static final Pattern SAMPLE_DESC = Pattern.compile("(\\d+|\\d+p\\d+)[Hh]?\\s+(\\S+)");
    /** growth dilution factor */
    private static final double GROWTH_FACTOR = 10.0;


    /**
     * Construct a single-experiment group.
     *
     * @param dir	directory of result files
     * @param id	ID of this group (usually derived from the date)
     *
     * @throws IOException
     */
    public SingleExperimentGroup(File dir, String id) throws IOException {
        super(dir, id);
    }

    @Override
    protected boolean isLayoutFile(String name) {
        // A layout file is a spreadsheet with the word "layout" in the name.
        return LAYOUT_FILE_NAME.matcher(name).matches();
    }

    @Override
    protected double computeTimePoint(File growthFile) {
        // The pattern tells us where to find the time point in the file name.
        var m = GROWTH_FILE_NAME.matcher(growthFile.getName());
        double retVal = 0.0;
        if (m.matches())
            retVal = parseTime(m.group(2));
        return retVal;
    }

    /**
     * @return the time point indicated by a time string.
     *
     * @param timeString	time string to convert
     */
    protected double parseTime(String timeString) {
        // A time of "seed" is 0.
        double retVal;
        if (timeString.contentEquals("seed"))
            retVal = 0.0;
        else {
            // The only tricky part is that we need to fix the decimal point.
            String time = StringUtils.replace(timeString, "p", ".");
            retVal = Double.valueOf(time);
        }
        return retVal;
    }

    @Override
    protected void readLayoutFile(File layoutFile) throws IOException {
        // Get the plate ID from the layout file.
        var m = LAYOUT_FILE_NAME.matcher(layoutFile.getName());
        if (! m.matches())
            this.plateId = "P1";
        else
            this.plateId = m.group(1);
        // Create an experiment entry for the plate.
        this.createExperiment(this.plateId);
        // The layout file is an Excel spreadsheet, so we have to use XSSF to open it.
        try (var workbook = new XSSFWorkbook(layoutFile)) {
            // Loop through the spreadsheet rows.  Column 1 contains a well ID, column 2 contains the
            // strain name in the new format.  IPTG wells are the same as normal wells, but with
            // four positions added to the initial letter (e.g. A12 becomes E12).
            var sheet = workbook.getSheetAt(0);
            for (var row : sheet) {
                String well = ExcelUtils.stringValue(row.getCell(0));
                // Note we add an identifier in front of the strain ID so the parser knows it's new-format.
                String strain = "str " + ExcelUtils.stringValue(row.getCell(1));
                // Store the normal and IPTG wells.
                this.store(this.plateId, strain, well, false);
                well = this.getIptgWell(well);
                this.store(this.plateId, strain, well, true);
            }
        } catch (InvalidFormatException e) {
            // Convert this to an IO exception.
            throw new IOException("Invalid Excel file format " + e.toString());
        }
    }

    @Override
    protected ExperimentGroup.SampleDesc parseSampleName(String data) {
        ExperimentGroup.SampleDesc retVal = null;
        // Note that anything which does not match the standard format is treated as blank.
        var m = SAMPLE_DESC.matcher(data);
        if (m.matches()) {
            // Group 1 is the time point, group 2 the well.
            double timePoint = this.parseTime(m.group(1));
            String well = m.group(2);
            retVal = new ExperimentGroup.SampleDesc(this.plateId, well, timePoint);
        }
        return retVal;
    }

    /**
     * Determine whether or not a file name is the name of a growth file.  A growth
     * file is a spreadsheet that starts with "96well", then a plate name, and then a
     * time point.
     *
     * @param name		name of the file
     *
     * @return TRUE if the file name indicates a growth file, else FALSE
     */
    @Override
    protected boolean isGrowthFile(String name) {
        return GROWTH_FILE_NAME.matcher(name).matches();
    }

    /**
     * @return the IPTG well for a corresponding normal well
     *
     * @param well	input well coordinates
     */
    protected String getIptgWell(String well) {
        char row = well.charAt(0);
        char iRow = (char) (row + 4);
        return iRow + well.substring(1);
    }

    /**
     * Read the growth data for a single-experiment group. Here the growth data is in a matrix organized
     * according to the structure of a plate.  The matrix is immediately below an "OD(600)" marker.  The
     * values are diluted by a factor of 10, which must be multiplied back in.  The time point and plate
     * ID are in the file name.
     *
     * @param growthFile	input growth file to read
     * @param time			the time point of this growth
     *
     * @throws IOException
     */
    @Override
    protected void readGrowthFile(File growthFile, double time) throws IOException {
        // Get the plate ID from the file name.
        var m = GROWTH_FILE_NAME.matcher(growthFile.getName());
        m.matches();
        String plate = m.group(1);
        // We will store the growth data in the plate results file.
        ExperimentData results = this.getPlateResults(plate);
        // Load the spreadsheet.
        try (var workbook = new XSSFWorkbook(growthFile)) {
            // Get the first sheet, which has our data in it.
            var sheet = workbook.getSheetAt(0);
            // Find the marker.
            var iter = sheet.iterator();
            if (! ExcelUtils.findMarker(iter, "OD(600)"))
                throw new IOException("No OD marker found in growth file " + growthFile + ".");
            else {
                // Now the iterator is positioned after the marker row.
                // The next row is the column headers.
                final var row0 = iter.next();
                List<String> colHeaders = IntStream.range(0, 14)
                        .mapToObj(i -> String.format("%d", (int) ExcelUtils.numValue(row0.getCell(i)))).collect(Collectors.toList());
                final int n = colHeaders.size();
                // Loop through the remaining rows.
                int storeCount = 0;
                while (iter.hasNext()) {
                    var row = iter.next();
                    // Get the row letter.
                    String rowID = ExcelUtils.stringValue(row.getCell(1));
                    // Loop through the cells.
                    for (int i = 2; i < n; i++) {
                        String well = rowID + colHeaders.get(i);
                        double growth = ExcelUtils.numValue(row.getCell(i));
                        // Here we have an actual growth value.  If there is no result for this
                        // time point and well, then it is a blank for which we have no data.
                        ExperimentData.Result result = results.getResult(well, time);
                        if (result != null) {
                            result.setGrowth(growth * GROWTH_FACTOR);
                            storeCount++;
                        }
                    }
                }
                log.info("{} growth values stored from {}.", storeCount, growthFile);
            }
        } catch (InvalidFormatException e) {
            throw new IOException("Invalid excel file format: " + e.getMessage());
        }
    }
}
