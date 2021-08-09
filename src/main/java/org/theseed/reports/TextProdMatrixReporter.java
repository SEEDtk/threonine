/**
 *
 */
package org.theseed.reports;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

/**
 * This reporter outputs the production matrix as a simple tab-delimited file, suitable for loading into a spreadsheet or
 * processing with standard software.
 *
 * @author Bruce Parrello
 *
 */
public class TextProdMatrixReporter extends ProdMatrixReporter {

    // FIELDS
    /** output writer */
    private PrintWriter writer;

    public TextProdMatrixReporter(File outFile) throws FileNotFoundException {
        super(outFile);
        this.writer = null;
    }

    @Override
    protected void initReport(OutputStream oStream) {
        this.writer = new PrintWriter(oStream);
    }

    @Override
    protected void writeHeaders(String[] columns) {
        this.writer.println("\t" + StringUtils.join(columns, '\t'));
    }

    @Override
    protected void writeRow(String label, double[] ds) {
        String dataLine = Arrays.stream(ds).mapToObj(x -> (Double.isNaN(x) ? "" : Double.toString(x))).collect(Collectors.joining("\t"));
        this.writer.println("D" + label + "\t" + dataLine);
    }

    @Override
    protected void cleanup() throws IOException {
        if (this.writer != null)
            this.writer.close();
    }

}
