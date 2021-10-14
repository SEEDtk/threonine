/**
 * 
 */
package org.theseed.sugar;

/**
 * This merger keeps the sugar values associated with the highest threonine production value.
 * Since this is an ongoing process, there is no need for a compute step.
 * 
 * @author Bruce Parrello
 *
 */
public class MaxSugarMerger extends SugarMerger {
	
	// FIELDS
	/** saved sugar values */
	private double[] sugarValues;
	/** saved threonine production value */
	private double prodValue;
	
	/**
	 * Construct a blank sugar merger.
	 */
	public MaxSugarMerger() {
		this.clear();
	}

	@Override
	public void clear() {
		this.prodValue = Double.NEGATIVE_INFINITY;
	}

	@Override
	public void merge(DataPoint values) {
		if (values.production > this.prodValue) {
			this.prodValue = values.production;
			this.sugarValues = values.sugarValue;
		}
	}

	@Override
	public double get(int i) {
		double retVal = 0.0;
		if (this.sugarValues != null)
			retVal = sugarValues[i];
		return retVal;
	}

	@Override
	public void compute() { }

}
