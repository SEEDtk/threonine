/**
 *
 */
package org.theseed.threonine;

import org.theseed.samples.SampleId;

/**
 * This sampling filter restricts consideration to samples using the known best-performing base criteria, so
 * the only variations are the source strain and the insert/delete combinations.
 *
 * @author Bruce Parrello
 *
 */
public class ChromosomeSampleFilter extends SampleFilter {

    public ChromosomeSampleFilter(IParms processor) {
        super(processor);
    }

    @Override
    public boolean acceptable(SampleId sample) {
        boolean retVal = (sample.isIPTG() && sample.getTimePoint() == 24.0);
        if (retVal)
            retVal = sample.getFragment(SampleId.OPERON_COL).contentEquals("TA1")
                    && sample.getFragment(SampleId.ASD_COL).contentEquals("asdT");
        return retVal;
    }

}
