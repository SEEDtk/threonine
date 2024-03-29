/**
 *
 */
package org.theseed.experiments;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.RegExUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This experiment group has multiple layout files stored as word documents.  Each layout document has a heading
 * paragraph with the text "Layout for set XXXX", where "XXXX" is the plate name.  Additional plates with the
 * same layout may be specified by adding them with comma delimiters (e.g. "XXXX, YYYY, ZZZZ).  The column paragraphs
 * are numbered list items with format "decimal" and contain part of a strain name.  The row paragraphs are
 * numbered list items with format "upperletter"  and contain "0" (indicating no change), or an additional
 * modifier for the strain name.  There may also be override paragraphs beginning with a letter-number combination
 * and a period.  These are NOT list items, and contain strains that ignore the standard row and column rules.
 * At any point, a strain name of "Blank" indicates no organism in the well.  Before the column paragraphs,
 * there may also be abbreviation lines of the form
 *
 * 		X=string
 *
 * where "X" is a letter and "string" is a replacement string. When the letter appears at the end of a strain
 * number, it is automatically replaced.  Thus, if
 *
 * 		A=ptac thrABC
 *
 * appears, then
 *
 * 		926A ppc aspC
 *
 * would become
 *
 * 		926 ptac thrABC ppc aspC
 *
 * Finally, the IPTG paragraph is of the form
 *
 * 		IPTG X=Y, X=Y, ..., X=Y
 *
 * where each X is an IPTG row and each Y is an original row from which it is copied.  The IPTG paragraph must be last.
 *
 * The time point for this group is stored in the file names-- in the middle for production files and at the end for growth files.
 *
 * The sample names consist of the plate ID and the well label with no additional text.
 *
 * @author Bruce Parrello
 *
 */
public class MultiExperimentGroup extends ExperimentGroup {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(MultiExperimentGroup.class);
    /** abbreviation definition pattern */
    private static final Pattern ABBR_LINE = Pattern.compile("([A-Z])=(.+)");
    /** abbreviation application pattern */
    private static final Pattern ABBR_CALL = Pattern.compile("(\\d+)([A-Z])(\\s.+)");
    /** override time string in sample name */
    protected static final Pattern SAMPLE_NAME = Pattern.compile("0?(.+?)(?:\\s+(\\d+)[Hh])?\\s+([A-Z]\\d+)");
    /** array of well letters */
    private static final String[] LETTERS = new String[] { "A", "B", "C", "D", "E", "F", "G", "H" };
    /** array of well numbers */
    private static final String[] NUMBERS = new String[] { "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12" };
    /** pattern for override line */
    private static final Pattern OVERRIDE_LINE = Pattern.compile("([A-Z]\\d+)\\.\\s+(.+)");
    /** pattern for IPTG line */
    private static final Pattern IPTG_LINE = Pattern.compile("IPTG\\s+(.+)");
    /** pattern for plate ID line */
    private static final Pattern PLATE_LINE = Pattern.compile("(?:Layout for sets?|Plates labell?ed)\\s+(.+)");
    /** pattern for non-ASCII characters */
    private static final Pattern BAD_CHARS = Pattern.compile("[^\\x00-\\x7F]");
    /** pattern for parsing time point from growth file name */
    private static final Pattern TIME_POINT = Pattern.compile(".+_(\\d+)h.+");
    /** pattern for a numbered no-plasmid plate */
    private static final Pattern NO_PLASMID_PLATE = Pattern.compile("no plasmid (\\d+)(.+)");

    /**
     * Construct the experiment group.
     *
     * @param dir	input directory
     * @param id	group ID
     *
     * @throws IOException
     */
    public MultiExperimentGroup(File dir, String id) throws IOException {
        super(dir, id);
    }

    @Override
    protected boolean isLayoutFile(String name) {
        return (StringUtils.endsWith(name, ".docx") && StringUtils.startsWithIgnoreCase(name, "layout"));
    }

    @Override
    protected double computeTimePoint(File fileName) {
        String baseName = fileName.getName();
        Matcher m = TIME_POINT.matcher(baseName);
        double retVal = this.getTimePoint();
        if (m.matches())
            retVal = Integer.valueOf(m.group(1));
        return retVal;
    }

    @Override
    protected void readLayoutFile(File layoutFile) throws IOException {
        // This is the final strain string map.  The overrides go directly in here.
        Map<String, String> strainMap = new HashMap<String, String>(100);
        // This is where we put the plate IDs.
        Set<String> plates = null;
        // This will hold the abbreviation data.
        Map<String, String> abbrMap = new TreeMap<String, String>();
        // Get access to the word document.
        try (FileInputStream fileStream = new FileInputStream(layoutFile);
            XWPFDocument document = new XWPFDocument(fileStream)) {
            // These iterate through the rows and columns.
            int row = 0;
            int col = 0;
            // This is set to TRUE if we find the IPTG paragraph.
            boolean iptgFound = false;
            // The row and column strings will be put in these arrays.
            String[] rowStrings = new String[LETTERS.length];
            String[] colStrings = new String[NUMBERS.length];
            // Loop through the paragraphs.
            for (XWPFParagraph para : document.getParagraphs()) {
                String line = getLine(para);
                if (line.startsWith("No plasmid"))
                    line = "";
                String numFmt = para.getNumFmt();
                if (numFmt != null) {
                    // Here we have a row or column.  Do the IPTG safety check, then figure out which it is.
                    if (iptgFound)
                        throw new IOException("Cannot have row or column data following IPTG paragraph.");
                    else {
                        switch (numFmt) {
                        case "upperLetter" :
                        case "lowerLetter" :
                            // Here we have a row.
                            rowStrings[row] = line;
                            row++;
                            break;
                        case "decimal" :
                            // Here we have a column.
                            colStrings[col] = this.abbrCheck(abbrMap, line);
                            col++;
                            break;
                        }
                    }
                } else {
                    // Here we have a special case:  an abbreviation, an override, or an IPTG spec.
                    Matcher m = ABBR_LINE.matcher(line);
                    if (m.matches())
                        abbrMap.put(m.group(1), m.group(2));
                    else {
                        m = OVERRIDE_LINE.matcher(line);
                        if (m.matches()) {
                            if (iptgFound)
                                throw new IOException("Cannot have override data following IPTG paragraph.");
                            // Store overrides directly in the strain map.
                            strainMap.put(m.group(1), m.group(2));
                        } else {
                            m = PLATE_LINE.matcher(line);
                            if (m.matches()) {
                                String plateString = StringUtils.removeEnd(m.group(1), ".");
                                plates = Set.of(plateString.split(",\\s*"));
                                for (String plate : plates)
                                    this.createExperiment(plate);
                                log.info("Processing plate layout.  Experiment list: {}.", StringUtils.join(plates, ", "));
                            } else {
                                m = IPTG_LINE.matcher(line);
                                if (m.matches()) {
                                    // Here we have an IPTG line.  The line contains instructions for copying rows.
                                    // The copied row contains the old strain string with a suffix of " +IPTG".
                                    String[] maps = m.group(1).split(",\\s+");
                                    for (String map : maps) {
                                        String to = map.substring(0, 1);
                                        String from = map.substring(2, 3);
                                        // Map the row string.  Here we find the proper array indices.
                                        int fromIdx = Arrays.binarySearch(LETTERS, from);
                                        int toIdx = Arrays.binarySearch(LETTERS, to);
                                        if (fromIdx < 0 || toIdx < 0)
                                            throw new IOException("Invalid IPTG mapping \"" + map + "\" found.");
                                        rowStrings[toIdx] = rowStrings[fromIdx] + " +IPTG";
                                        // Update the overrides.  Each override key that has the from-letter gets changed
                                        // to the to-letter and the IPTG suffix is added to the strain string.  Blanks are
                                        // not changed. (Blank +IPTG is the same as Blank.)
                                        List<String> wellsToChange = strainMap.keySet().stream()
                                                .filter(x -> x.startsWith(from)).collect(Collectors.toList());
                                        for (String well : wellsToChange) {
                                            String strain = strainMap.get(well);
                                            if (! strain.contentEquals("Blank"))
                                                strain += " +IPTG";
                                            strainMap.put(to + well.substring(1), strain);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Now we have processed the whole layout file.  We must assemble the non-overridden wells from
            // the row and column data.
            for (int r = 0; r < rowStrings.length; r++) {
                String rowString = rowStrings[r];
                String rowLabel = LETTERS[r];
                if (rowString != null) {
                    // Turn the row string into a suffix.  "0" becomes a null string, and every thing else
                    // gets a space prefixed.
                    if (rowString.startsWith("0"))
                        rowString = StringUtils.replaceOnce(rowString, "0", "");
                    else
                        rowString = " " + rowString;
                    // Loop through the columns.
                    for (int c = 0; c < colStrings.length; c++) {
                        String colString = colStrings[c];
                        String well = rowLabel + NUMBERS[c];
                        // Insure we have a valid column and the well is not overridden.
                        if (colString != null && ! colString.contentEquals("Blank") && ! strainMap.containsKey(well)) {
                            // Form this well's strain name.
                            strainMap.put(well, colString + rowString);
                        }
                    }
                }
            }
            log.info("{} row mappings, {} column mappings, {} strain mappings.", row, col, strainMap.size());
        }
        // We are finished with the layout file, but we have to convert the strain mapping information into results.
        if (plates == null)
            throw new IOException("No plate ID found.");
        for (Map.Entry<String, String> strainEntry : strainMap.entrySet()) {
            String well = strainEntry.getKey();
            String strainString = strainEntry.getValue();
            if (! strainString.contentEquals("Blank")) {
                // Check for IPTG.
                boolean iptgFlag = false;
                if (strainString.contains(" +IPTG")) {
                    strainString = StringUtils.remove(strainString, " +IPTG");
                    iptgFlag = true;
                }
                // Store this strain and associate it with this well.
                for (String plate : plates)
                    this.store(plate, strainString, well, iptgFlag);
            }
        }
    }

    /**
     * @return the input line with any abbreviations applied
     *
     * @param abbrMap	abbreviation map, mapping capital letters to replacement strings
     * @param line		input line to process
     * @throws IOException
     */
    private String abbrCheck(Map<String, String> abbrMap, String line) throws IOException {
        String retVal;
        Matcher m = ABBR_CALL.matcher(line);
        if (! m.matches())
            retVal = line;
        else {
            String replacement = abbrMap.get(m.group(2));
            if (replacement == null)
                throw new IOException("Invalid abbreviation character in \"" + line + "\".");
            retVal = m.group(1) + " " + replacement + m.group(3);
        }
        return retVal;
    }

    /**
     * @return the text representation of the paragraph
     *
     * @param para	paragraph to convert to text
     */
    private static String getLine(XWPFParagraph para) {
        String line = para.getText();
        // Convert deltas to Ds.  Note this is the Word version of a delta, not a normal unicode delta.
        line = StringUtils.replaceChars(line, '\uf044', 'D');
        // Remove other unicode characters.
        String line2 = RegExUtils.replaceAll(line, BAD_CHARS, " ");
        // Trim spaces.
        String retVal = StringUtils.trimToEmpty(line2);
        return retVal;
    }

    @Override
    protected ExperimentGroup.SampleDesc parseSampleName(String data) {
        ExperimentGroup.SampleDesc retVal = null;
        // Sometimes there is a "plate" prefix we have to remove.
        String trimmed = StringUtils.removeStart(data, "PLATE ");
        // In addition, there is special translation for "NO PLASMID" plus a number..
        Matcher m = NO_PLASMID_PLATE.matcher(trimmed);
        if (m.matches())
            trimmed = "NONE" + m.group(1) + m.group(2);
        m = SAMPLE_NAME.matcher(trimmed);
        if (m.matches()) {
            // Trim the time from the set ID (if any).
            String setId = m.group(1);
            if (setId.contains(" "))
                setId = StringUtils.replace(setId, " ", "");
            double time = this.getTimePoint();
            if (m.group(2) != null)
                time = Integer.valueOf(m.group(2));
            retVal = new ExperimentGroup.SampleDesc(setId, m.group(3), time);
        }
        return retVal;
    }

}
