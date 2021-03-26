/**
 *
 */
package org.theseed.rna;

/**
 * This is the base class for an object that computes the baseline expression value for a feature in an RNA
 * database.  For example, the baseline can come from a particular sample, as an average of all the values for
 * the feature, or from an external file.
 *
 * @author Bruce Parrello
 *
 */
public abstract class BaselineComputer {

    /**
     * @return the baseline value for the specified data row
     *
     * @param row	RNA seq data row for the feature of interest
     */
    public abstract double getBaseline(RnaData.Row row);

    /**
     * Enumeration for the different baseline computation types
     */
    public static enum Type {
        TRIMEAN {
            @Override
            public BaselineComputer create(IBaselineParameters processor) {
                return new TrimeanBaselineComputer();
            }
        }, FILE {
            @Override
            public BaselineComputer create(IBaselineParameters processor) {
                return new FileBaselineComputer(processor.getFile());
            }
        }, SAMPLE {
            @Override
            public BaselineComputer create(IBaselineParameters processor) {
                return new SampleBaselineComputer(processor.getSample(), processor.getData());
            }
        };

        /**
         * Create a baseline computer of this type.
         *
         * @param processor		object containing the parameters for baseline computation
         *
         * @return a baseline computer of the specified type
         */
        public abstract BaselineComputer create(IBaselineParameters processor);

    }
}
