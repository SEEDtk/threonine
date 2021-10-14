/**
 * 
 */
package org.theseed.sugar;

import org.theseed.reports.MeanComputer;

import java.util.ArrayList;
import java.util.List;

/**
 * This sugar merger computes a mean of the specified type for each sugar value.
 *  
 * @author Bruce Parrello
 *
 */
public class MeanSugarMerger extends SugarMerger {
	
	// FIELDS
	/** list of data points */
	private List<SugarMerger.DataPoint> dataPoints;
	/** mean computation object */
	private MeanComputer computer;
	/** list of final mean values */
	private double[] results;
	
	/**
	 * Construct a sugar merger.
	 * 
	 * @param type	type of mean to use for the merge
	 */
	public MeanSugarMerger(MeanComputer.Type type) {
		// Get ready to compute the appropriate mean.
		this.computer = type.create();
		// Initialize the data point list.
		this.dataPoints = new ArrayList<SugarMerger.DataPoint>();
	}

	@Override
	public void clear() {
		this.dataPoints.clear();
		this.results = null;
	}

	@Override
	public void merge(DataPoint values) {
		this.dataPoints.add(values);
	}

	@Override
	public void compute() {
		// Only proceed if we have results.
		if (! this.dataPoints.isEmpty()) {
			// Compute the number of sugar values.
			int n = this.dataPoints.get(0).sugarValue.length;
			// For each sugar value, we compute a mean.
			this.results = new double[n];
			List<Double> inputs = new ArrayList<Double>(this.dataPoints.size());
			for (int i = 0; i < n; i++) {
				final int iVal = i;
				this.dataPoints.stream().forEach(x -> inputs.add(x.sugarValue[iVal]));
				this.results[i] = this.computer.goodMean(inputs);
				inputs.clear();
			}
		}
	}
	
	@Override
	public double get(int i) {
		double retVal = 0.0;
		if (this.results != null)
			retVal = this.results[i];
		return retVal;
	}

}
