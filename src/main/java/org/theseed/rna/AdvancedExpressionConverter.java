/**
 *
 */
package org.theseed.rna;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is a more complex version of the RNA seq expression converter that handles grouped features.  It also supports a
 * more limited set of conversions.
 *
 * @author Bruce Parrello
 *
 */
public abstract class AdvancedExpressionConverter {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(AdvancedExpressionConverter.class);
    /** controlling command processor */
    private IParms processor;

    /**
     * This interface is used to access the subclass parameters in the controlling command processor.
     */
    public interface IParms {

        /**
         * @return the file containing the baseline values
         */
        public File getBaseFile();

    }

    /**
     * This enumeration is used to determine the conversion type.
     */
    public static enum Type {
        RAW {
            @Override
            public AdvancedExpressionConverter create(IParms processor) {
                return new AdvancedExpressionConverter.Null(processor);
            }
        }, TRIAGE {
            @Override
            public AdvancedExpressionConverter create(IParms processor) {
                return new TriageAdvancedExpressionConverter(processor);
            }
        };

        /**
         * @return an advanced expression-level converter of this type
         *
         * @param processor		controlling command processor
         */
        public abstract AdvancedExpressionConverter create(IParms processor);
    }

    /**
     * Construct an advanced expression-level converter.
     *
     * @param processor		controlling command processor
     */
    public AdvancedExpressionConverter(IParms processor) {
        this.processor = processor;
    }

    /**
     * Initialize the expression-level converter.
     *
     * @throws IOException
     */
    public void initialize() throws IOException {
        this.initialize(this.processor);
    }

    /**
     * Initialize the subclass data structures of expression-level converter.
     *
     * @param processor		controlling command processor
     *
     * @throws IOException
     */
    protected abstract void initialize(IParms processor) throws IOException;

    /**
     * Set up the column definitions.  The information provided tells us which group goes in each output column
     * and which features are in each group.
     *
     * @param colTitles		ordered list of column titles
     * @param filterMap		map of column titles to component feature IDs
     *
     * @throws IOException
     */
    protected abstract void defineColumns(SortedSet<String> colTitles, Map<String, Set<String>> filterMap) throws IOException;

    /**
     * Convert a summarized value to its final form.
     *
     * @param colTitle		title of the output column
     * @param value			raw value in the output column
     *
     * @return converted value of the output column
     */
    protected abstract double convert(String colTitle, double value);

    /**
     * This is a simple converter that passes the original value back unchanged.
     */
    public static class Null extends AdvancedExpressionConverter {

        public Null(IParms processor) {
            super(processor);
        }

        @Override
        protected void initialize(IParms processor) {
        }

        @Override
        protected void defineColumns(SortedSet<String> colTitles, Map<String, Set<String>> filterMap) {
        }

        @Override
        protected double convert(String colTitle, double value) {
            return value;
        }

    }



}
