/**
 *
 */
package org.theseed.rna;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.rna.RnaData.Row;
import org.theseed.threonine.RnaSeqBaseProcessor;

/**
 * This is the base class for RNA Seq expression data feature filtering.  It is responsible not only for determining which features to
 * keep, but also grouping them together into operons or regulons if needed.
 *
 * @author Bruce Parrello
 *
 */
public abstract class RnaSeqFeatureFilter {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(RnaSeqFeatureFilter.class);
    /** map of column titles to component features */
    private Map<String, Set<String>> filterMap;
    /** set of column titles */
    private SortedSet<String> colTitles;
    /** map of feature iDs to column indexes in sample array */
    private Map<String, Integer> columnMap;
    /** controlling processor */
    private IParms processor;
    /** expression data matrix, keyed by sample ID */
    private Map<String, double[]> sampleData;
    /** expected number of features */
    public static final int NUM_FEATURES = 4000;

    /**
     * Interface describing parameters from the controlling object.
     */
    public interface IParms {

        /**
         * @return the name of the file containing the filtering data
         */
        File getFilterFile();

    }

    /**
     * Enumeration of the different filter types.
     */
    public static enum Type {
        NONE {
            @Override
            public RnaSeqFeatureFilter create(IParms processor) {
                return new RnaSeqFeatureFilter.Null(processor);
            }
        }, SUBSYSTEMS {
            @Override
            public RnaSeqFeatureFilter create(IParms processor) {
                return new SimpleRnaSeqFeatureFilter.Subsystems(processor);
            }
        }, MODULONS {
            @Override
            public RnaSeqFeatureFilter create(IParms processor) {
                return new SimpleRnaSeqFeatureFilter.Modulons(processor);
            }
        }, FILE {
            @Override
            public RnaSeqFeatureFilter create(IParms processor) {
                return new SimpleRnaSeqFeatureFilter.FileList(processor);
            }
        }, GROUPS {
            @Override
            public RnaSeqFeatureFilter create(IParms processor) {
                return new GroupRnaSeqFeatureFilter(processor);
            }
        };

        /**
         * Create a feature filter of this type.
         *
         * @param processor		controlling command processor
         *
         * @return the feature filter created
         */
        public abstract RnaSeqFeatureFilter create(IParms processor);
    }

    /**
     * Construct an RNA seq expression feature filter.
     *
     * @param processor		controlling command processor
     */
    public RnaSeqFeatureFilter(IParms processor) {
        this.processor = processor;
    }

    /**
     * Initialize this filter's data structures.
     *
     * @throws IOException
     */
    public void initialize() throws IOException {
        this.colTitles = new TreeSet<String>();
        this.filterMap = new HashMap<String, Set<String>>(NUM_FEATURES);
        this.columnMap = new HashMap<String, Integer>(NUM_FEATURES);
        this.initialize(this.processor);
    }
    /**
     * Initialize the subclass data structures.
     *
     * @param processor		controlling command processor
     *
     * @throws IOException
     */
    protected abstract void initialize(IParms processor) throws IOException;

    /**
     * @return TRUE if the specified feature should be used, else FALSE
     *
     * @param feat	feature of interest
     */
    public abstract boolean checkFeature(RnaFeatureData feat);

    /**
     * Record the specified row's feature as one that is being kept.
     *
     * @param row	RNA data row for the feature
     */
    public void saveFeature(Row row) {
        String fid = row.getFeat().getId();
        String group = this.computeGroup(fid, row);
        Set<String> groupFidSet = this.filterMap.computeIfAbsent(group, x -> new TreeSet<String>());
        groupFidSet.add(row.getFeat().getId());
        this.colTitles.add(group);
        this.columnMap.put(fid, this.columnMap.size());
    }

    /**
     * Compute the group name for this feature.  The default is to use the gene name with a suffix based on the peg number,
     * which yields one feature per group.  The subclass can override to provide a different group name.
     *
     * @param fid	ID of the feature of interest
     * @param row	data row for the feature
     *
     * @return the group name for this feature
     */
    protected String computeGroup(String fid, Row row) {
        return RnaSeqBaseProcessor.computeGeneId(row);
    }

    /**
     * @return the column titles as a list
     */
    public List<String> getTitles() {
        return new ArrayList<String>(this.colTitles);
    }

    /**
     * Set up to save the expression data for each sample.
     *
     * @param jobMap	map of job names to job descriptors
     */
    public void initializeRows(Collection<String> jobs) {
        // Create the sample data map.
        this.sampleData = new HashMap<String, double[]>(jobs.size());
        // For each job, we create an array of NaN values.
        for (String jobName : jobs) {
            double[] values = new double[this.columnMap.size()];
            Arrays.fill(values, Double.NaN);
            this.sampleData.put(jobName, values);
        }
    }

    /**
     * Record a feature's weight value for a sample.
     *
     * @param sample	ID of the sample of interest
     * @param fid		ID of the feature of interest
     * @param weight	expression value of the feature in that sample.
     */
    public void processValue(String sample, String fid, double weight) {
        // Get the value array for this sample.
        double[] sampleValues = this.sampleData.get(sample);
        // Get the array index for this feature.  Note that even though it is in the group, it may not have a value,
        // so we need to check the hash.
        if (this.columnMap.containsKey(fid)) {
            int idx = this.columnMap.get(fid);
            // Store the value.
            sampleValues[idx] = weight;
        }
    }

    /**
     * This method is called after the data from all the rows has been stored.
     *
     * @param converter		expression converter to apply to the values
     *
     * @returns		a map from each sample ID to its array of numeric expression values
     *
     * @throws IOEXception
     */
    public Map<String, double[]> finishRows(AdvancedExpressionConverter converter) throws IOException {
        // Create the return map.
        Map<String, double[]> retVal = new HashMap<String, double[]>(this.sampleData.size());
        // Initialize the converter.
        converter.defineColumns(this.colTitles, this.filterMap);
        // Loop through the samples, processing each one individually.
        for (Map.Entry<String, double[]> sampleEntry : this.sampleData.entrySet()) {
            // Summarize the data into columns.
            double[] newSampleData = this.processRow(sampleEntry.getKey(), sampleEntry.getValue(), converter);
            // Store the converted data in the output map.
            retVal.put(sampleEntry.getKey(), newSampleData);
        }
        return retVal;
    }

    /**
     * Process a row of raw sample data to produce summarized sample data.
     *
     * @param sample	name of the relevant sample
     * @param rawData	un-summarized sample data
     *
     * @return the array of summarized sample data
     */
    private double[] processRow(String sample, double[] rawData, AdvancedExpressionConverter converter) {
        double[] retVal = new double[this.colTitles.size()];
        int i = 0;
        // For each column, take the mean of the components.
        for (String colTitle : this.colTitles) {
            int n = 0;
            retVal[i] = 0.0;
            Set<String> colFids = this.filterMap.get(colTitle);
            for (String colFid : colFids) {
                double v = rawData[this.getColIdx(colFid)];
                if (! Double.isNaN(v)) {
                    retVal[i] += v;
                    n++;
                }
            }
            // Compute the mean.
            if (n > 1)
                retVal[i] /= n;
            // Convert using the expression converter.
            retVal[i] = converter.convert(colTitle, retVal[i]);
            i++;
        }
        return retVal;
    }

    /**
     * @return the column index for a feature
     *
     * @param fid	ID of the feature of interest
     */
    protected int getColIdx(String fid) {
        return this.columnMap.get(fid);
    }

    /**
     * This is the simplest type of feature filter:  it passes everything.
     *
     * @author Bruce Parrello
     *
     */
    public static class Null extends RnaSeqFeatureFilter {

        public Null(IParms processor) {
            super(processor);
        }

        @Override
        protected void initialize(IParms processor) {
        }

        @Override
        public boolean checkFeature(RnaFeatureData feat) {
            return true;
        }

    }


}
