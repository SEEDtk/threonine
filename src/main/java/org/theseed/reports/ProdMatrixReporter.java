/**
 *
 */
package org.theseed.reports;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.theseed.samples.SampleId;
import org.theseed.samples.SetComparator;

/**
 * This is the base class for production matrix reports.
 *
 * @author Bruce Parrello
 *
 */
public abstract class ProdMatrixReporter implements AutoCloseable {

    // FIELDS
    /** output stream */
    private OutputStream outStream;
    /** map of column names to indices */
    private Map<Set<String>, Integer> columnMap;
    /** map of row names to indices */
    private Map<Set<String>, Integer> rowMap;
    /** matrix of sample values */
    private double[][] prodLevels;

    /**
     * This enum represents the different report types.
     */
    public static enum Type {
        TEXT {
            @Override
            public ProdMatrixReporter create(File outFile) throws IOException {
                return new TextProdMatrixReporter(outFile);
            }
        };

        /**
         * @return a reporting object of this type
         *
         * @param outFile	output file for the report
         */
        public abstract ProdMatrixReporter create(File outFile) throws IOException;
    }

    /**
     * Construct a production matrix report writer.
     *
     * @param outFile	output file
     *
     * @throws FileNotFoundException
     */
    public ProdMatrixReporter(File outFile) throws FileNotFoundException {
        this.outStream = new FileOutputStream(outFile);
        // Denote we don't have an output array yet.
        this.prodLevels = null;
    }

    /**
     * Initialize the report for output.  This should set up the output, but should not write the header, which happens later.
     */
    public void openReport() {
        this.initReport(this.outStream);
    }

    /**
     * Initialize the report.
     *
     * @param oStream		output stream for the report
     */
    protected abstract void initReport(OutputStream oStream);

    /**
     * Specify the ordered list of column names.
     *
     * @param ordering	list containing protein ordering for inserts
     * @param columns	collection of insert combinations representing the columns
     */
    public void setColumns(List<String> ordering, Collection<String> columns) {
        this.columnMap = this.orderLabels('-', ordering, columns);
    }

    /**
     * Specify the ordered list of row names.
     *
     * @param ordering	list containing protein ordering for deletes
     * @param columns	collection of delete combinations representing the rows
     */
    public void setRows(List<String> ordering, Collection<String> rows) {
        this.rowMap = this.orderLabels('D', ordering, rows);
    }

    /**
     * Create the map of names to indices for the rows or columns in the output report.
     *
     * @param delim			delimiter used in the input strings
     * @param ordering		list of set elements in priority order
     * @param labels		list of label strings
     *
     * @return a map from each label to its proper row/column in the output
     */
    private Map<Set<String>, Integer> orderLabels(char delim, List<String> ordering, Collection<String> labels) {
        // Create a list of the labels after converting them to sets.
        List<Set<String>> labelSets = labels.stream().map(x -> computeProts(delim, x)).collect(Collectors.toList());
        // Sort them using the set comparator.
        Collections.sort(labelSets, new SetComparator(ordering));
        // The tricky part here is that xxx-yyy-zzz and zzz-xxx-yyy are the same set but not the same string, so we need to get rid of duplicates.
        // Fortunately the duplicates will have sorted next to each other.
        Iterator<Set<String>> labelIter = labelSets.iterator();
        Set<String> prev = labelIter.next();
        while (labelIter.hasNext()) {
            Set<String> curr = labelIter.next();
            if (curr.equals(prev))
                labelIter.remove();
            else
                prev = curr;
        }
        // Built the output map.  Each set is mapped to its position.
        Map<Set<String>, Integer> retVal = new HashMap<Set<String>, Integer>(labelSets.size());
        for (int i = 0; i < labelSets.size(); i++)
            retVal.put(labelSets.get(i), i);
        return retVal;
    }

    /**
     * @return the set of protein strings in an insert or delete specifier.
     *
     * @param delim			delimiter ('-' for inserts, 'D' for deletes)
     * @param protString	protein string indicating the set of proteins
     */
    public static Set<String> computeProts(char delim, String protString) {
        Set<String> retVal = new TreeSet<String>();
        for (String prot : StringUtils.split(protString, delim)) {
            if (! prot.contentEquals("000"))
                    retVal.add(prot);
        }
        return retVal;
    }

    /**
     * Specify the value of a sample.
     *
     * @param sample	ID of the sample being processed
     * @param value		production level of the sample
     */
    private void setValue(SampleId sample, double value) {
        // Compute the column index.
        Set<String> colSet = sample.getInserts();
        int colIdx = this.columnMap.get(colSet);
        // Compute the row index.
        Set<String> rowSet = sample.getDeletes();
        int rowIdx = this.rowMap.get(rowSet);
        // Store the value.
        this.prodLevels[rowIdx][colIdx] = value;
    }

    /**
     * Output the report.
     *
     * @param prodMap	map of sample IDs to production values
     *
     */
    public void closeReport(Map<SampleId, Double> prodMap) {
        // Get an array of column headers and an array of row labels.
        String[] columns = this.getLabels('-', this.columnMap);
        String[] rows = this.getLabels('D', this.rowMap);
        // Write the column headers.
        this.writeHeaders(columns);
        // Create the output matrix.
        this.prodLevels = new double[rows.length][columns.length];
        Arrays.stream(this.prodLevels).forEach(x -> Arrays.fill(x, 0.0));
        // Loop through the production map, storing production levels.
        for (Map.Entry<SampleId, Double> sampleEntry : prodMap.entrySet())
            this.setValue(sampleEntry.getKey(), sampleEntry.getValue());
        // Now we can write out the data rows.
        for (int i = 0; i < rows.length; i++)
            this.writeRow(rows[i], this.prodLevels[i]);
    }

    /**
     * Write a data row.
     *
     * @param label		row label
     * @param ds		array of column values for the row.
     */
    protected abstract void writeRow(String label, double[] ds);

    /**
     * Write the column headers.
     *
     * @param columns	array of column headers, in order
     */
    protected abstract void writeHeaders(String[] columns);

    /**
     * Compute an array of labels (row or column) in the proper order.  The incoming map tells us the
     * array index for each possible protein set.
     *
     * @param delim			delimiter to use between set elements in the label strings
     * @param labelMap		map of protein sets to column indices
     *
     * @return an array of label strings in order
     */
    private String[] getLabels(char delim, Map<Set<String>, Integer> labelMap) {
        String delimString = Character.toString(delim);
        String[] retVal = new String[labelMap.size()];
        for (Map.Entry<Set<String>, Integer> labelEntry : labelMap.entrySet()) {
            // Convert the set to a label string.  Note we must sort in case we are dealing with hash sets.
            String label;
            Set<String> protSet = labelEntry.getKey();
            if (protSet.isEmpty())
                label = "000";
            else
                label = protSet.stream().sorted().collect(Collectors.joining(delimString));
            // Store the label string in the correct position.
            retVal[labelEntry.getValue()] = label;
        }
        return retVal;
    }

    /**
     * Clean up and release any resources used by this reporter.
     */
    protected abstract void cleanup() throws IOException;

    @Override
    public void close() throws IOException {
        this.cleanup();
        this.outStream.close();
    }

}
