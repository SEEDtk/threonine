/**
 *
 */
package org.theseed.experiments;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This experiment group type has a single layout for all plates, but each plate has a different gene insertion plasmid.
 *
 * The layout file is a spreadsheet that describes the strains in each small plate.  Row H is always blank here.
 * The bulk of the strain is described in the appropriate cell starting at spreadsheet location C6.  The gene
 * insertions ate described in a column beginning at C25.  Each contains a plate ID, a period or space, a space, and
 * then the insertion gene.  "No plasmid" is used for no insertion.
 *
 * The sample names do not contain a time point, so the default time point is used.  The sample names contain a short
 * string (usually "mfm" or "set N" with an optional leading 0) before the plate ID and well label.
 *
 * Layout files are spreadsheets with the word "layout" at the beginning of the name.
 *
 * @author Bruce Parrello
 *
 */
public class SetExperimentGroup extends ExperimentGroup {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(SetExperimentGroup.class);
    /** sample name pattern */
    private static final Pattern SAMPLE_NAME = Pattern.compile("0?(?:SET \\d+|MFM)\\s+(\\S+)\\s+([A-Z]\\d+)");
    /** plate column strings */
    private static final String[] COL_NAMES = new String[] {"", "", "1", "2", "3", "4", "5",
            "6", "7", "8", "9", "10", "11", "12" };
    /** first IPTG column */
    private static final int IPTG_COL = 7 + 1;

    /**
     * Construct the experiment group.
     *
     * @param dir	input directory
     * @param id	group ID
     *
     * @throws IOException
     */
    public SetExperimentGroup(File dir, String id) throws IOException {
        super(dir, id);
    }

    @Override
    protected boolean isLayoutFile(String name) {
        return (StringUtils.endsWith(name, ".xlsx") && StringUtils.startsWithIgnoreCase(name, "layout"));
    }

    @Override
    protected double computeTimePoint(File growthFile) {
        return this.getTimePoint();
    }

    @Override
    protected void readLayoutFile(File layoutFile) throws IOException {
        try (InputStream fileStream = new FileInputStream(layoutFile);
                Workbook workbook = new XSSFWorkbook(fileStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            // Now we have access to the main sheet.  We will put the plate list in here.
            Map<String, String> plateMap = new HashMap<String, String>(10);
            // Verify that this is a real layout sheet.
            Cell cell = getCell(sheet, 23, 2);
            String value = stringValue(cell);
            if (! value.contains("mental plasmids"))
                throw new IOException("\"" + layoutFile + "\" is not a valid layout file.  C24 does not have a recognized header.");
            // Loop through the plasmids.
            int rowNum = 24;
            boolean done = false;
            while (! done) {
                cell = getCell(sheet, rowNum, 2);
                value = stringValue(cell);
                if (value.isEmpty())
                    done = true;
                else {
                    String[] parts = value.split("\\.?\\s+", 2);
                    if (NONE_SET.contains(parts[1].toUpperCase())) {
                        parts[1] = "";
                        parts[0] = "NONE";
                    } else
                        parts[1] = " " + StringUtils.trimToEmpty(parts[1]);
                    plateMap.put(parts[0].toUpperCase(), parts[1]);
                    this.createExperiment(parts[0]);
                }
                rowNum++;
            }
            log.info("{} plates found in plate list.", plateMap.size());
            // Now we process the main layout grid.  We look for rows with a single letter in the B column.
            int cellCount = 0;
            for (Row row : sheet) {
                String label = stringValue(row.getCell(1));
                if (label != null && label.length() == 1) {
                    // Here we have a plate row definition. Loop through the columns.
                    for (int c = 2; c < COL_NAMES.length; c++) {
                        // Compute the well ID.
                        String well = label + COL_NAMES[c];
                        // Compute the base strain name.  Note we have to replace LFs with spaces, since
                        // some of the strain names have been manually split into multiple lines for
                        // readability.
                        boolean iptgFlag = (c >= IPTG_COL);
                        String strain = StringUtils.trimToEmpty(StringUtils.replace(stringValue(row.getCell(c)), "\n", " "));
                        // Is this a filled cell?
                        if (! strain.isEmpty() && ! BLANK_CELL.matcher(strain).matches()) {
                            // Yes. Loop through the plates, adding the appropriate suffix and setting up a result
                            // space.
                            for (Map.Entry<String, String> plateInfo : plateMap.entrySet())
                                super.store(plateInfo.getKey(), strain + plateInfo.getValue(), well, iptgFlag);
                            cellCount++;
                        }
                    }
                }
            }
            log.info("{} cells found in layout file.", cellCount);
        }
    }

    @Override
    protected SampleDesc parseSampleName(String data) {
        ExperimentGroup.SampleDesc retVal = null;
        Matcher m = SAMPLE_NAME.matcher(data);
        if (m.matches())
            retVal = new ExperimentGroup.SampleDesc(m.group(1), m.group(2), this.getTimePoint());
        return retVal;
    }

}
