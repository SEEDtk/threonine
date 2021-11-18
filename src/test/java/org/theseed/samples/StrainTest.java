/**
 * 
 */
package org.theseed.samples;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.theseed.test.Matchers.*;

import org.junit.jupiter.api.Test;

/**
 * @author Bruce Parrello
 *
 */
public class StrainTest {

	@Test
	public void test() {
		SampleId strain1 = new SampleId("7_0_TA1_C_asdT_zwf_DlysC_0_24_M1").asStrain();
		SampleId strain2 = new SampleId("7_0_TA1_C_asdT_zwf_DlysC_I_12_M2").asStrain();
		assertThat(strain1, equalTo(strain2));
		assertThat(strain1.isConstructed(), isTrue());
		assertThat(strain2.isConstructed(), isTrue());
		SampleId strain3 = new SampleId("7_0_TA1_C_asdT_zwf_DlysCDmetL_0_24_M1").asStrain();
		strain2 = new SampleId("7_0_TA1_C_asdT_zwf_DmetLDlysC_I_4p5_M3").asStrain();
		assertThat(strain3.isConstructed(), isTrue());
		assertThat(strain2.isConstructed(), isTrue());
		assertThat(strain3, equalTo(strain2));
		assertThat(strain3, not(equalTo(strain1)));
		SampleId sample1 = new SampleId("7_0_0_0_asdO_000_D000_I_5p5_M1");
		assertThat(sample1.isConstructed(), isFalse());
	}

}
