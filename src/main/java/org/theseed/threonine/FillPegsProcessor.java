/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.basic.BaseProcessor;
import org.theseed.basic.ParseFailureException;
import org.theseed.genome.Feature;
import org.theseed.genome.Genome;
import org.theseed.io.LineReader;
import org.theseed.io.TabbedLineReader;

/**
 * This is a simple pipeline that reads a GTO and adds the missing pegs to a tab-delimited snips file coming in on the
 * standard input.  For each peg not in the original file, the peg will be added along with its group information and
 * all the snip columns will be left blank.
 *
 * The standard input should be a tab-delimited file with headers containing feature IDs in the first column.  The
 * standard output will be a modified version of the input.  The positional parameters are the name of the GTO file
 * containing the feature IDs and the name of the full genome's groups file.
 *
 * The command-line options are as follows.
 *
 * -h	display command-line usage
 * -v	display more detailed log messages
 * -i	name of the input file (if not STDIN)
 * -o	name of the output file (if not STDOUT)
 *
 *
 * @author Bruce Parrello
 *
 */
public class FillPegsProcessor extends BaseProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(FillPegsProcessor.class);
    /** map of feature IDs to input lines */
    private Map<String, String> lineMap;
    /** map of feature IDs to group strings */
    private Map<String, String> groupMap;
    /** input file stream */
    private InputStream inStream;
    /** output file stream */
    private OutputStream outStream;
    /** header for output file */
    private String header;
    /** data string for blank lines */
    private String dataSuffix;
    /** expected number of genome features */
    private static final int EXPECTED_FEATURES = 4000;

    // COMMAND-LINE OPTIONS

    /** input file name */
    @Option(name = "--input", aliases = { "-i" }, metaVar = "in.snips.tbl", usage = "input file (if not STDIN)")
    private File inFile;

    /** output file name */
    @Option(name = "--output", aliases = { "-o" }, metaVar = "out.snips.tbl", usage = "output file (if not STDOUT)")
    private File outFile;

    /** genome input file */
    @Argument(index = 0, metaVar = "base_genome.gto", usage = "base genome GTO file", required = true)
    private File genomeFile;

    /** groups input file */
    @Argument(index = 1, metaVar = "groups.tbl", usage = "groups input file", required = true)
    private File groupFile;

    @Override
    protected void setDefaults() {
        this.inFile = null;
        this.outFile = null;
    }

    @Override
    protected boolean validateParms() throws IOException, ParseFailureException {
        // Verify the input.
        if (this.inFile == null) {
            log.info("Input will be from the standard input.");
            this.inStream = System.in;
        } else if (! this.inFile.canRead())
            throw new FileNotFoundException("Input file " + this.inFile + " is not found or unreadable.");
        else {
            log.info("Input will be read from {}.", this.inFile);
            this.inStream = new FileInputStream(this.inFile);
        }
        // Verify the genome file.
        if (! this.genomeFile.canRead())
            throw new FileNotFoundException("Genome file " + this.genomeFile + " is not found or unreadable.");
        // Verify the group file.
        if (! this.groupFile.canRead())
            throw new FileNotFoundException("Group file " + this.groupFile + " is not found or unreadable.");
        // Set up the output.
        if (this.outFile == null) {
            log.info("Output will be to the standard output.");
            this.outStream = System.out;
        } else {
            log.info("Output will be to {}.", this.outFile);
            this.outStream = new FileOutputStream(this.outFile);
        }
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        try {
            // Get all the groups.
            this.groupMap = new HashMap<String, String>(EXPECTED_FEATURES);
            try (TabbedLineReader groupStream = new TabbedLineReader(this.groupFile)) {
                // We will build the group strings in here.
                StringBuilder groupString = new StringBuilder(100);
                for (TabbedLineReader.Line line : groupStream) {
                    // Clear the string buffer.
                    groupString.setLength(0);
                    // Get the feature ID.
                    String fid = line.get(0);
                    // Build the group string.
                    String mods = line.get(1);
                    groupString.append(mods);
                    String ar = String.format("AR%d", line.getInt(2));
                    if (groupString.length() > 0) groupString.append(", ");
                    groupString.append(ar);
                    String ops = line.get(2);
                    if (! ops.isEmpty())
                        groupString.append(", ").append(ops);
                    String subs = line.get(3);
                    if (! subs.isEmpty())
                        groupString.append(", ").append(subs);
                    // Update the map.
                    this.groupMap.put(fid, groupString.toString());
                }
            }
            log.info("{} features with groups found.", this.groupMap.size());
            // Now get all the input lines.
            try (LineReader inStream = new LineReader(this.inStream)) {
                Iterator<String> iter = inStream.iterator();
                // Save the header.
                this.header = iter.next();
                // Parse the header to compute the data string for blank lines.
                this.dataSuffix = StringUtils.repeat(" \t", StringUtils.split(this.header, '\t').length - 4) + " ";
                // Save the data lines.
                this.lineMap = new HashMap<String, String>(EXPECTED_FEATURES);
                for (String line : inStream) {
                    // Strip out the feature ID.
                    String fid = StringUtils.substringBefore(line, "\t");
                    // Save the line in the line map.
                    this.lineMap.put(fid, line);
                }
            }
            log.info("{} features found in input file.", this.lineMap.size());
            // Now the last step is to create the output file from the genome.
            Genome genome = new Genome(this.genomeFile);
            log.info("Genome {} read from {}.", genome, this.genomeFile);
            int count = 0;
            try (PrintWriter writer = new PrintWriter(this.outStream)) {
                // Start with the header.
                writer.println(this.header);
                // Loop through the pegs.
                for (Feature feat : genome.getPegs()) {
                    String fid = feat.getId();
                    // If we already have an output line, write it now.
                    if (this.lineMap.containsKey(fid))
                        writer.println(this.lineMap.get(fid));
                    else {
                        // Build a line from the function and the group.
                        String function = feat.getPegFunction();
                        String groups = this.groupMap.getOrDefault(fid, "");
                        writer.format("%s\t%s\t%s\t%s%n", fid, function, groups, this.dataSuffix);
                        count++;
                    }
                }
            }
            log.info("{} new lines added to output.", count);
        } finally {
            if (this.inFile != null)
                this.inStream.close();
            if (this.outFile != null)
                this.outStream.close();
        }
    }

}
