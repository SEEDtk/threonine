/**
 *
 */
package org.theseed.threonine;

import org.theseed.samples.SampleId;

/**
 * This is the base class for a sample filter.  The sample filter eliminates samples according to various criteria.
 * Note that impossible samples are eliminated by a different process.  This is for tunable eliminations.
 *
 * @author Bruce Parrello
 *
 */
public abstract class SampleFilter {

    public static enum Type {
        NONE {
            @Override
            public SampleFilter create(IParms processor) {
                return new SampleFilter.None(processor);
            }
        }, LOW_COST {
            @Override
            public SampleFilter create(IParms processor) {
                return new LowCostSampleFilter(processor);
            }
        };

        public abstract SampleFilter create(SampleFilter.IParms processor);

    }

    /**
     * This interface is used to specify parameters the filter may need from the client.
     */
    public interface IParms {

        /**
         * @return the maximum number of deletes allowed
         */
        public int getMaxDeletes();

        /**
         * @return the maximum number of inserts allowed
         */
        public int getMaxInserts();

        /**
         * @return TRUE if plasmid operons are allowed
         */
        public boolean getPlasmidFlag();

    }

    /**
     * Construct a sample filter.
     *
     * @param processor		controlling command processor
     */
    public SampleFilter(IParms processor) { }


    /**
     * @return TRUE if the sample is acceptable, else FALSE
     *
     * @param sample	sample to check
     */
    public abstract boolean acceptable(SampleId sample);

    /**
     * This is the simplest filter-- it accepts everything.
     */
    public static class None extends SampleFilter {

        public None(IParms processor) {
            super(processor);
        }

        @Override
        public boolean acceptable(SampleId sample) {
            return true;
        }

    }
}
