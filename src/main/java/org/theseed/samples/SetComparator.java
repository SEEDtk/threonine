/**
 *
 */
package org.theseed.samples;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * This is a comparator for comparing sets of strings.  The constructor takes as a parameter an ordered
 * list of the possible strings in each set.  The first string is most significant, and later strings
 * are less significant.  The first string in the ordered list that differs in membership is located,
 * and the set without that string is compared as less.
 *
 * @author Bruce Parrello
 *
 */
public class SetComparator implements Comparator<Set<String>> {

    // FIELDS
    /** list of possible set elements, in order from most to least significant */
    private List<String> possibles;

    /**
     * Construct a set comparator using a specified string order.
     *
     * @param possibleStrings	list of possible strings, in order from most to least significant
     */
    public SetComparator(List<String> possibleStrings) {
        this.possibles = possibleStrings;
    }

    @Override
    public int compare(Set<String> o1, Set<String> o2) {
        int retVal = 0;
        for (int i = 0; i < this.possibles.size() && retVal == 0; i++) {
            String possible = this.possibles.get(i);
            // This is -1 if o1 does not contain and o2 contains, 1 if o1 contains
            // and o2 does not, and 0 if both containment tests have the same result.
            retVal = Boolean.compare(o1.contains(possible), o2.contains(possible));
        }
        return retVal;
    }

}
