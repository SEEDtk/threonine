/**
 *
 */
package org.theseed.reports;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.theseed.io.LineReader;
import org.theseed.samples.SampleId;

/**
 * @author Bruce Parrello
 *
 */
class SampleIterationTest {

    private static final Set<String> GOOD_HOSTS = Set.of("7", "M");

    @Test
    void test() throws IOException {
        File oFile = new File("data", "originals.txt");
        Set<SampleId> originals = LineReader.readSet(oFile).stream().map(x -> new SampleId(x))
                .collect(Collectors.toSet());
        assertThat(originals.size(), equalTo(3));
        var formatter = new ThrSampleFormatter();
        File choiceFile = new File("data", "choices.txt");
        formatter.setupChoices(choiceFile);
        Iterator<SampleId> iter = formatter.new SampleGenerator(originals);
        Set<SampleId> found = new HashSet<SampleId>();
        int count = 0;
        while (iter.hasNext()) {
            found.add(iter.next());
            count++;
        }
        // Insure there are no duplicates.
        assertThat(found.size(), equalTo(count));
        for (SampleId foundSample : found) {
            String label = foundSample.toString();
            assertThat(label, originals.stream().anyMatch(x -> x.isSameBase(foundSample)), equalTo(true));
            var inserts = foundSample.getInserts();
            var deletes = foundSample.getDeletes();
            inserts.retainAll(deletes);
            assertThat(label, inserts.size(), equalTo(0));
            assertThat(label, foundSample.getFragment(0), in(GOOD_HOSTS));
        }

    }

}
