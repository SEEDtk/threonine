/**
 *
 */
package org.theseed.threonine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import org.junit.jupiter.api.Test;

/**
 * @author Bruce Parrello
 *
 */
public class ThrTest {

    @Test
    public void growthTest() {
        GrowthData test = new GrowthData("name1", 4.5);
        test.merge(0.120259798, 2.09, "A1", "B1");
        test.merge(0.070732925, 1.85, "A2", "B2");
        test.merge(0.115688087, 1.8, "A3", "B3");
        test.merge(0.005205063, 2.05, "A4", "B4");
        assertThat(test.getOldStrain(), equalTo("name1"));
        assertThat(test.getProduction(), closeTo(0.07797146825, 0.0001));
        assertThat(test.getDensity(), closeTo(1.9475, 0.0001));
        assertThat(test.getNormalizedProduction(), closeTo(0.040646, 0.0001));
        assertThat(test.getProductionRate(), closeTo(0.01732699, 0.0001));
        assertThat(test.getOrigins(), equalTo("A1:B1, A2:B2, A3:B3, A4:B4"));
    }


}
