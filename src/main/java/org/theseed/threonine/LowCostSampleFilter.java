/**
 *
 */
package org.theseed.threonine;

import org.theseed.samples.SampleId;

/**
 * This sample filter limits the samples to low-cost mutations.  In particular, it prohibits plasmids and limits the number
 * of deletes.
 *
 * @author Bruce Parrello
 *
 */
public class LowCostSampleFilter extends SampleFilter {

    // FIELDS
    /** maximum nubmer of deletes */
    private int maxDeletes;
    /** maximum nubmer of inserts */
    private int maxInserts;
    /** TRUE to allow plasmid operons */
    private boolean plasmidFlag;

    public LowCostSampleFilter(IParms processor) {
        super(processor);
        this.maxDeletes = processor.getMaxDeletes();
        this.maxInserts = processor.getMaxInserts();
        this.plasmidFlag = processor.getPlasmidFlag();
    }

    @Override
    public boolean acceptable(SampleId sample) {
        boolean retVal = true;
        if (! this.plasmidFlag && sample.getFragment(3).contentEquals("P"))
            retVal = false;
        else if (sample.getDeletes().size() > this.maxDeletes)
            retVal = false;
        else if (sample.getInserts().size() > this.maxInserts)
            retVal = false;
        return retVal;
    }

}
