/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.Argument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.io.TabbedLineReader;
import org.theseed.samples.SampleId;
import org.theseed.utils.BasePipeProcessor;
import org.theseed.utils.ParseFailureException;

/**
 * This command performs a strain renaming operation.  This occurs when one or more source strains have been
 * mislabelled.  The input file is the combined master table, and the output is a modified combined master.
 * In the modified combined master, certain strain names will be updated, and the "fixed" column marked to
 * indicate that the strain has been updated.
 *
 * The strain change will consist of replacing the input source strain with a new value.  If the new value is
 * the same as the old one, the input row will be unchanged, but still marked as fixed.  If an input row is
 * already marked as fixed, it will retain its fixed marking and remain unchanged.
 *
 * The positional parameter is the name of the file containing the replacement specifications.  Each replacement
 * specification will consist of an old strain name, the old source strain number, and the replacement source
 * strain number.  The specification file will be tab-delimited with headers.
 *
 * The command-line options are as follows:
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 * -i	input combined master file name (if not STDIN)
 * -o	output combined master file name (if not STDOUT)
 *
 * @author Bruce Parrello
 *
 */
public class ReStrainProcessor extends BasePipeProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(ReStrainProcessor.class);
    /** renaming map, sample -> [old, new] */
    private TreeMap<SampleId, Map.Entry<String, String>> renamingMap;
    /** strain ID input column index */
    private int strainCol;
    /** threonine production input column index */
    private int thrCol;
    /** fixed-flag column index */
    private int fixedCol;
    /** suspect-flag column index */
    private int suspectCol;

    // COMMAND-LINE OPTIONS

    /** renaming-specification file */
    @Argument(index = 0, metaVar = "new_strain_data.tbl", usage = "name of file containing strain-renaming rules")
    private File renameFile;

    @Override
    protected void setPipeDefaults() {
    }

    @Override
    protected void validatePipeInput(TabbedLineReader inputStream) throws IOException {
        // Find the relevant columns in the input.  If the input file is bad, one of these will fail.
        this.strainCol = inputStream.findField("strain_lower");
        this.thrCol = inputStream.findField("Thr");
        this.fixedCol = inputStream.findField("fixed");
        this.suspectCol = inputStream.findField("Suspect");
    }

    @Override
    protected void validatePipeParms() throws IOException, ParseFailureException {
        // Insure the rename-file exists.
        if (! this.renameFile.canRead())
            throw new FileNotFoundException("Rename-specification file " + this.renameFile + " not found or unreadable.");
        // Read it in and build a map from it.  Note the map is keyed by the chromosome data ONLY, not the
        // full sample ID.
        this.renamingMap = new TreeMap<SampleId, Map.Entry<String, String>>(new SampleId.ChromoSort());
        int count = 0;
        try (TabbedLineReader rStream = new TabbedLineReader(this.renameFile)) {
            for (TabbedLineReader.Line line : rStream) {
                SampleId key = SampleId.translate(line.get(0), 24.0, false, "M1");
                Map.Entry<String, String> mapping = new AbstractMap.SimpleEntry<String, String>(line.get(1), line.get(2));
                if (this.renamingMap.containsKey(key))
                    log.warn("Duplicate key \"{}\" in specifications file.", line.get(0));
                else
                    this.renamingMap.put(key, mapping);
                count++;
            }
        }
        log.info("{} mappings found in {} input lines of {}.", this.renamingMap.size(), count, this.renameFile);
    }

    @Override
    protected void runPipeline(TabbedLineReader inputStream, PrintWriter writer) throws Exception {
        // Start the output file.
        writer.println(inputStream.header());
        // We need some counters.
        int inCount = 0;
        int fixCount = 0;
        int preFixCount = 0;
        int keepCount = 0;
        int skipCount = 0;
        int badCount = 0;
        int delCount = 0;
        // We also need to fix up blank columns.
        String[] oldCols = new String[inputStream.size()];
        Arrays.setAll(oldCols, i -> "");
        // Now loop through the input file, producing output.
        for (TabbedLineReader.Line line : inputStream) {
            inCount++;
            // Skip if there is no threonine amount.
            String thr = line.get(this.thrCol);
            if (StringUtils.isBlank(thr))
                delCount++;
            else {
                // Fix all the blank input columns.
                String[] cols = line.getFields();
                for (int i = 0; i < inputStream.size(); i++) {
                    if (i != this.fixedCol && StringUtils.isBlank(cols[i]))
                        cols[i] = oldCols[i];
                    oldCols[i] = cols[i];
                }
                // If this sample is already fixed, write it unchanged.
                if (line.getFlag(this.fixedCol)) {
                    preFixCount++;
                } else if (StringUtils.containsIgnoreCase(cols[0], "blank")) {
                    // Never map blanks.
                    cols[this.fixedCol] = "Y";
                    keepCount++;
                } else {
                    // Check the sample ID.
                    SampleId lineSample = SampleId.translate(cols[0], 24.0, false, "M1");
                    var mapping = this.renamingMap.get(lineSample);
                    if (mapping == null) {
                        // No mapping. Keep the line unchanged.
                        skipCount++;
                    } else {
                        var oldStrain = mapping.getKey();
                        var newStrain = mapping.getValue();
                        if (newStrain.startsWith("?")) {
                            // Here we have an unknown sample.  We mark it suspect.
                            cols[this.suspectCol] = "1";
                            cols[this.fixedCol] = "?";
                            badCount++;
                        } else if (oldStrain.contentEquals(newStrain)) {
                            // Here we are fixing, but the strain is not changed.
                            cols[this.fixedCol] = "Y";
                            keepCount++;
                        } else {
                            // Here we must update the strain.
                            cols[this.fixedCol] = "Y";
                            cols[this.strainCol] = StringUtils.replaceOnce(cols[this.strainCol], oldStrain, newStrain);
                            fixCount++;
                        }
                    }
                }
                // Write the fixed-up line.
                writer.println(StringUtils.join(cols, '\t'));
            }
        }
        // Write the statistics.
        log.info("{} lines read.  {} already fixed.  {} confirmed unchanged.", inCount, preFixCount, keepCount);
        log.info("{} marked suspect.  {} unmapped.  {} deleted.  {} fixed.", badCount, skipCount, delCount, fixCount);
    }

}
