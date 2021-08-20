/**
 *
 */
package org.theseed.samples;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.utils.IDescribable;

/**
 * This object is a differential sample table for a single feature category.  Each sample is identified by its configuration
 * without the specified category's value determined, and for each such identification, it will contain a list of
 * the production values for each cagtegory value.
 *
 * @author Bruce Parrello
 *
 */
public abstract class SampleDiffTable {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(SampleDiffTable.class);

    /** table of feature mappings */
    private Map<String, List<Entry>> featureTable;
    /** set of feature choices */
    private SortedSet<String> choices;

    /**
     * This enum describes all the different categories.  Each sample we process must be entered into all
     * of the categories.
     */
    public static enum Category implements IDescribable {
        STRAIN {
            @Override
            public SampleDiffTable create() {
                return new SampleDiffTable.Simple(0);
            }

            @Override
            public String getDescription() {
                return "Base Strain";
            }
        }, OPERON {
            @Override
            public SampleDiffTable create() {
                return new SampleDiffTable.Operon();
            }

            @Override
            public String getDescription() {
                return "Threonine Operon";
            }
        }, ASD {
            @Override
            public SampleDiffTable create() {
                return new SampleDiffTable.Simple(4);
            }

            @Override
            public String getDescription() {
                return "Asd Protein";
            }
        }, INSERTS {
            @Override
            public SampleDiffTable create() {
                return new SampleDiffTable.Simple(5);
            }

            @Override
            public String getDescription() {
                return "Gene Insertions";
            }
        }, DELETES {
            @Override
            public SampleDiffTable create() {
                return new SampleDiffTable.Simple(6);
            }

            @Override
            public String getDescription() {
                return "Gene Deletions";
            }
        }, IPTG {
            @Override
            public SampleDiffTable create() {
                return new SampleDiffTable.Simple(7);
            }

            @Override
            public String getDescription() {
                return "IPTG Induction";
            }
        }, TIME {
            @Override
            public SampleDiffTable create() {
                return new SampleDiffTable.Simple(8);
            }

            @Override
            public String getDescription() {
                return "Time Point";
            }
        };

        /**
         * @return a sample difference table for this category
         */
        public abstract SampleDiffTable create();
    }

     /**
     * This is the entry for a single sample.  It describes the sample's value for the feature category and its
     * production level.
     */
    public class Entry {

        /** choice for this sample in the feature of interest */
        private String choice;
        /** production value */
        private double production;

        /**
         * Construct a new entry.
         *
         * @param choice		value of the category feature
         * @param production	production level
         */
        protected Entry(String choice, double production) {
            this.choice = choice;
            this.production = production;
        }

        /**
         * @return the category choice
         */
        public String getChoice() {
            return this.choice;
        }

        /**
         * @return the production value
         */
        public double getProduction() {
            return this.production;
        }

    }

    /**
     * Construct a blank, empty sample difference table.
     */
    public SampleDiffTable() {
        this.featureTable = new TreeMap<String, List<Entry>>();
        this.choices = new TreeSet<String>();
    }

    /**
     * Add a sample to a table.
     *
     * @param sample		ID of sample to add
     * @param production	production value
     */
    public abstract void addSample(SampleId sample, double production);

    /**
     * Insert a sample into the table.,
     *
     * @param abstractSampleId	sample ID with the missing columns removed
     * @param choice			value of the category feature
     * @param production		production level
     */
    protected void insertSample(String abstractSampleId, String choice, double production) {
        List<Entry> entryList = this.featureTable.computeIfAbsent(abstractSampleId, x -> new ArrayList<Entry>());
        entryList.add(new Entry(choice, production));
        this.getChoices().add(choice);
    }

    /**
     * Add a simple-fragment sample to the table.  A simple fragment is only coded in a single fragment position.
     *
     * @param sample		ID of sample to add
     * @param fragIdx		index of variable fragment for this category
     * @param production	production level
     */
    protected void addFragmentSimple(SampleId sample, int fragIdx, double production) {
        String generic = sample.replaceFragment(fragIdx, "X");
        this.insertSample(generic, sample.getFragment(fragIdx), production);
    }

    /**
     * @return the choice strings for this table's category
     */
    public SortedSet<String> getChoices() {
        return choices;
    }

    /**
     * This subclass handles simple fragments.
     */
    public static class Simple extends SampleDiffTable {

        /** fragment index */
        private int fragIdx;

        /**
         * Construct a simple-fragment table for a given fragment.
         *
         * @param idx	index of the fragment of interest
         */
        public Simple(int idx) {
            super();
            this.fragIdx = idx;
        }

        @Override
        public void addSample(SampleId sample, double production) {
            this.addFragmentSimple(sample, this.fragIdx, production);
        }

    }

    /**
     * This subclass handles the operon fragment.
     */
    public static class Operon extends SampleDiffTable {

        @Override
        public void addSample(SampleId sample, double production) {
            String generic = sample.genericOperon();
            this.insertSample(generic, sample.getFragment(2), production);
        }

    }

    /**
     * @return the sample entries in this table
     */
    public Set<Map.Entry<String, List<Entry>>> getEntries() {
        return this.featureTable.entrySet();
    }

    /**
     * @return the list of entries for a generic sample ID (or NULL if there is none)
     *
     * @param sampleId	generic sample ID whose entries are desired
     */
    public List<Entry> getSampleEntries(String sampleId) {
        return this.featureTable.get(sampleId);
    }

    /**
     * @return 	the generic sample IDs in this table sorted by maximum production value (highest
     * 			to lowest
     */
    public List<String> getSamples() {
        List<String> retVal = new ArrayList<String>(this.featureTable.keySet());
        Collections.sort(retVal, this.new IdSorter());
        return retVal;
    }

    /**
     * This sorts the IDs in a useful order, by maximum production value (descending)
     */
    private class IdSorter implements Comparator<String> {

        /** map of IDs to maximum production value */
        private Map<String, Double> idMap;

        public IdSorter() {
            this.idMap = new HashMap<String, Double>(SampleDiffTable.this.featureTable.size());
            for (Map.Entry<String, List<Entry>> entry : SampleDiffTable.this.getEntries()) {
                double max = 0.0;
                for (Entry choiceEntry : entry.getValue()) {
                    if (choiceEntry.getProduction() > max)
                        max = choiceEntry.getProduction();
                }
                idMap.put(entry.getKey(), max);
            }
        }

        @Override
        public int compare(String o1, String o2) {
            int retVal = Double.compare(this.idMap.get(o2), this.idMap.get(o1));
            if (retVal == 0)
                retVal = o1.compareTo(o2);
            return retVal;
        }

    }


}
