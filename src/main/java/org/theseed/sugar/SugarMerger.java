/**
 * 
 */
package org.theseed.sugar;

import org.theseed.reports.MeanComputer;

/**
 * This object takes in multiple glucose measurements and merges them into a single result.  Because
 * the merging method may depend on the associated threonine values, these are passed in as well.
 * 
 * @author Bruce Parrello
 *
 */
public abstract class SugarMerger {
	
	/**
	 * This interface allows the merger to query the controlling command processor for parameters.
	 */
	public interface IParms {

		/**
		 * @return the type of mean to use
		 */
		public MeanComputer.Type getMeanType();
		
	}
	
	/**
	 * This enum describes the different types of mergers.
	 */
	public static enum Type {
		MAX {
			@Override
			public SugarMerger create(IParms processor) {
				return new MaxSugarMerger();
			}
		}, MEAN {
			@Override
			public SugarMerger create(IParms processor) {
				return new MeanSugarMerger(processor.getMeanType());
			}
		};
		
		/**
		 * @return a merger object of this type
		 * 
		 * @param processor		controlling command processor
		 */
		public abstract SugarMerger create(IParms processor);
		
	}
	
	/**
	 * This object describes one data point-- a production value and a sugar value. 
	 */
	public static class DataPoint {
		
		protected double production;
		protected double[] sugarValue;
		
		/**
		 * Create the data point.
		 * 
		 * @param prod		threonine production value
		 * @param sugar		array of sugar values
		 */
		public DataPoint(double prod, double[] sugar) {
			this.production = prod;
			this.sugarValue = sugar;
		}
		
	}
	
	/**
	 * Reset the sugar merger to a blank state
	 */
	public abstract void clear();

	/**
	 * Merge a data point into this object.
	 * 
	 * @param values	data point to merge
	 */
	public abstract void merge(DataPoint values);

	/**
	 * @return the indicated merged sugar value
	 * 
	 * @param i		index of the desired value in the datapoint array
	 */
	public abstract double get(int i);

	/**
	 * Close off the merge and compute the final values.
	 */
	public abstract void compute();
	
}
