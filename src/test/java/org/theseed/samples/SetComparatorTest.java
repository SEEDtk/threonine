/**
 *
 */
package org.theseed.samples;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * @author Bruce Parrello
 *
 */
public class SetComparatorTest {

    @Test
    public void testComparison() {
        SetComparator comp = new SetComparator(Arrays.asList("aceBA", "aspC", "pntAB", "ppc", "pyc", "rhtA", "zwf"));
        Set<String> set1 = new HashSet<String>(Arrays.asList("aceBA", "pntAB", "rhtA"));
        Set<String> set2 = new HashSet<String>(Arrays.asList("aceBA", "pntAB", "zwf"));
        assertThat(comp.compare(set1, set2), greaterThan(0));
        assertThat(comp.compare(set2, set1), lessThan(0));
        set2 = new HashSet<String>(Arrays.asList("aceBA", "pntAB", "rhtA"));
        assertThat(comp.compare(set1, set2), equalTo(0));
        assertThat(comp.compare(set2, set1), equalTo(0));
        set2 = new HashSet<String>(Arrays.asList("pntAB", "rhtA"));
        assertThat(comp.compare(set1, set2), greaterThan(0));
        set2 = new HashSet<String>(Arrays.asList("aceBA", "aspC", "pntAB"));
        assertThat(comp.compare(set1, set2), lessThan(0));
    }

}
