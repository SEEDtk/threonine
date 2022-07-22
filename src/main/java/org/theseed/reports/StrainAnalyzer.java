/**
 *
 */
package org.theseed.reports;

import java.util.HashMap;
import java.util.Map;

import org.theseed.samples.SampleId;

/**
 * This class tracks prediction/production pairs for strains in a run.  We keep the highest prediction
 * value and the highest production value for each strain.  A utility is provided to convert the result
 * to a standard PredictionAnalyzer.
 *
 * @author Bruce Parrello
 *
 */
public class StrainAnalyzer {

    // FIELDS
    /** map of strain IDs to prediction/production data */
    private Map<String, PredProd> strainMap;

    /**
     * Construct an empty strain analyzer.
     */
    public StrainAnalyzer() {
        this.strainMap = new HashMap<String, PredProd>(1000);
    }

    /**
     * Record a sample's prediction and production.
     *
     * @param sample	sample ID
     * @param pred		predicted value
     * @param prod		actual value
     */
    public void add(SampleId sample, double pred, double prod) {
        String strain = sample.toStrain();
        PredProd pair = this.strainMap.get(strain);
        if (pair == null)
            this.strainMap.put(strain, new PredProd(pred, prod));
        else {
            if (pred > pair.getPrediction()) pair.setPrediction(pred);
            if (prod > pair.getProduction()) pair.setProduction(prod);
        }
    }

    /**
     * @return a prediction analyzer for the strains
     */
    public PredictionAnalyzer toAnalyzer() {
        return new PredictionAnalyzer(this.strainMap.values());
    }

}
