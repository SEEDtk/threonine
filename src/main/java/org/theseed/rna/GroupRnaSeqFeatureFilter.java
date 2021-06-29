/**
 *
 */
package org.theseed.rna;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.theseed.io.TabbedLineReader;
import org.theseed.rna.RnaData.Row;

/**
 * This class uses a file to associate each feature with a group.  The filter file contains
 * a feature ID in the first column and a group ID in the second.  Only features in a group
 * will be passed by the filter.
 *
 * @author Bruce Parrello
 *
 */
public class GroupRnaSeqFeatureFilter extends RnaSeqFeatureFilter {

    // FIELDS
    /** map of feature IDs to group IDs */
    private Map<String, String> groupMap;

    public GroupRnaSeqFeatureFilter(IParms processor) {
        super(processor);
    }

    @Override
    protected void initialize(IParms processor) throws IOException {
        // Get the filter file.
        File filterFile = processor.getFilterFile();
        if (! filterFile.canRead())
            throw new FileNotFoundException("Group filtering file " + filterFile + " is not found or unreadable.");
        // Initialize the group map.
        this.groupMap = new HashMap<String, String>(NUM_FEATURES);
        // Loop through the filter file.
        try (TabbedLineReader groupStream = new TabbedLineReader(filterFile)) {
            for (TabbedLineReader.Line line : groupStream) {
                String group = line.get(1);
                if (! group.isEmpty())
                    this.groupMap.put(line.get(0), line.get(1));
            }
        }
        log.info("{} features read from group file {}.", this.groupMap.size(), filterFile);
    }

    @Override
    public boolean checkFeature(RnaFeatureData feat) {
        return this.groupMap.containsKey(feat.getId());
    }

    @Override
    protected String computeGroup(String fid, Row row) {
        return this.groupMap.get(fid);
    }

}
