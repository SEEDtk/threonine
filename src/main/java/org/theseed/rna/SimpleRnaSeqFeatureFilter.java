/**
 *
 */
package org.theseed.rna;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.theseed.genome.Feature;
import org.theseed.genome.Genome;
import org.theseed.io.TabbedLineReader;

/**
 * This subclass performs simple filtering.  The group name is the default (gene name followed by peg number), and the
 * final subclass determines the set of acceptable features during initialization.
 *
 * @author Bruce Parrello
 *
 */
public abstract class SimpleRnaSeqFeatureFilter extends RnaSeqFeatureFilter {

    // FIELDS
    /** set of acceptable feature IDs */
    private Set<String> goodFids;

    public SimpleRnaSeqFeatureFilter(IParms processor) {
        super(processor);
    }

    @Override
    protected final void initialize(IParms processor) throws IOException {
        // Create the good-feature set.
        this.goodFids = new HashSet<String>(NUM_FEATURES);
        // Get the filter file and verify that we can read it.
        File filterFile = processor.getFilterFile();
        if (! filterFile.canRead())
            throw new FileNotFoundException("Filter file " + filterFile + " is not found or unreadable.");
        this.selectFeatures(filterFile);
        log.info("{} features selected from {}.", this.goodFids.size(), filterFile);
    }

    /**
     * Read the filter file to determine the acceptable features.
     *
     * @param filterFile	file containing feature information
     */
    protected abstract void selectFeatures(File filterFile) throws IOException;

    @Override
    public final boolean checkFeature(RnaFeatureData feat) {
        return this.goodFids.contains(feat.getId());
    }

    /**
     * Add the specified feature ID to the list of good features.
     *
     * @param fid	feature ID to add
     */
    protected void addFeature(String fid) {
        this.goodFids.add(fid);
    }

    /**
     * This subclass reads the list of acceptable features from a file.  The file should have the feature IDs
     * in the first column.
     */
    public static class FileList extends SimpleRnaSeqFeatureFilter {

        public FileList(IParms processor) {
            super(processor);
        }

        @Override
        protected void selectFeatures(File filterFile) throws IOException {
            try (TabbedLineReader fidStream = new TabbedLineReader(filterFile)) {
                for (TabbedLineReader.Line line : fidStream)
                    this.addFeature(line.get(0));
            }
        }

    }

    /**
     * This subclass reads the list of acceptable features from a groups file.  Only features in modulons are kept.
     * The groups file has the feature IDs in the first column and the modulon list in the second.
     */
    public static class Modulons extends SimpleRnaSeqFeatureFilter {

        public Modulons(IParms processor) {
            super(processor);
        }

        @Override
        protected void selectFeatures(File filterFile) throws IOException {
            try (TabbedLineReader modStream = new TabbedLineReader(filterFile)) {
                for (TabbedLineReader.Line line : modStream) {
                    if (! line.get(1).isEmpty())
                        this.addFeature(line.get(0));
                }
            }
        }

    }

    /**
     * This class reads a GTO and selects only the features in subsystems.
     */
    public static class Subsystems extends SimpleRnaSeqFeatureFilter {

        public Subsystems(IParms processor) {
            super(processor);
        }

        @Override
        protected void selectFeatures(File filterFile) throws IOException {
            Genome genome = new Genome(filterFile);
            for (Feature feat : genome.getPegs()) {
                if (! feat.getSubsystems().isEmpty())
                    this.addFeature(feat.getId());
            }
        }

    }

}
