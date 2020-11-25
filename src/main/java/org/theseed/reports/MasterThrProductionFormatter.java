/**
 *
 */
package org.theseed.reports;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.theseed.threonine.SampleId;

/**
 * This produces an alternate form of the output designed for import into Excel.  The file is a tab-delimited
 * text file with the sample ID split into separate columns.
 *
 * @author Bruce Parrello
 *
 */
public class MasterThrProductionFormatter extends ThrProductionFormatter {

    // FIELDS
    /** output writer */
    private PrintWriter writer;
    /** number of output columns */
    private int outCols;

    /**
     * Create the master formatter.
     *
     * @param outFile	output file
     *
     * @throws FileNotFoundException
     */
    public MasterThrProductionFormatter(File outFile) throws FileNotFoundException {
        super(outFile);
        this.writer = new PrintWriter(outFile);
    }

    @Override
    protected void openReport() {
        // Get the list of possible insertions.
        String[] inserts = this.getInsertChoices();
        String insertCols = StringUtils.join(inserts, "\t");
        // Get the list of possible deletions.
        String[] deletes = this.getDeleteChoices();
        String deleteCols = Arrays.stream(deletes).map(x -> "D" + x).collect(Collectors.joining("\t"));
        // Compute the column count.
        this.outCols = 12 + inserts.length + deletes.length;
        // Write the file headings.
        this.writer.println("sample\thost\tDtrhABC\tnew operon\tlocation\tasd\tiptg\t" + insertCols + "\t" + deleteCols
                + "\ttime\tproduction\tdensity\tnormalized\trate");
    }

    @Override
    public void writeSample(String sampleId, double production, double density) {
        // Parse the sample ID and output the first 5 columns.
        SampleId sample = new SampleId(sampleId);
        List<String> cols = new ArrayList<String>(this.outCols);
        cols.add(sampleId);
        String[] fragments = sample.getBaseFragments();
        for (int i = 0; i < 5; i++)
            cols.add(fragments[i]);
        // Output the IPTG flag.
        cols.add(sample.isIPTG() ? "1" : "0");
        // Now the inserts.
        String[] inserts = this.getInsertChoices();
        String sampleInsert = fragments[SampleId.INSERT_COL];
        for (int i = 0; i < inserts.length; i++)
            cols.add(sampleInsert.contentEquals(inserts[i]) ? "1" : "0");
        // Next the deletes.
        String[] deletes = this.getDeleteChoices();
        Set<String> actualDeletes = sample.getDeletes();
        for (int i = 0; i < deletes.length; i++)
            cols.add(actualDeletes.contains(deletes[i]) ? "1" : "0");
        // Finally, the floating-point stuff.
        cols.add(Double.toString(sample.getTimePoint()));
        cols.add(Double.toString(production));
        cols.add(Double.toString(density));
        double normalized = (density == 0.0 ? 0.0 : production / density);
        cols.add(Double.toString(normalized));
        cols.add(Double.toString(production / sample.getTimePoint()));
        // Write the data line.
        this.writer.println(StringUtils.join(cols, "\t"));
    }

    @Override
    protected void closeReport() {
        this.writer.close();
    }

}
