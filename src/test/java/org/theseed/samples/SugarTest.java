/**
 *
 */
package org.theseed.samples;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.theseed.test.Matchers.*;

import org.junit.Test;

/**
 * @author Bruce Parrello
 *
 */
public class SugarTest {

    @Test
    public void test() {
        SugarUsage.setLevels(40.0, 1.1);
        SugarUsage testUsage = new SugarUsage(40.0);
        assertThat(testUsage.getUsage(), equalTo(0.0));
        assertThat(testUsage.isSuspicious(), isFalse());
        testUsage = new SugarUsage(37.0);
        assertThat(testUsage.getUsage(), equalTo(3.0));
        assertThat(testUsage.isSuspicious(), isFalse());
        testUsage = new SugarUsage(41.0);
        assertThat(testUsage.getUsage(), equalTo(0.0));
        assertThat(testUsage.isSuspicious(), isFalse());
        testUsage = new SugarUsage(45.0);
        assertThat(testUsage.getUsage(), equalTo(0.0));
        assertThat(testUsage.isSuspicious(), isTrue());
    }

}
