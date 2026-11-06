package com.vitallink.api.care;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Reading the NPI Registry's answers (recorded shape; the live call is checked by hand). */
class DoctorSearchTest {

    static final String ONE = """
            {"result_count":1,"results":[{"number":"1234567893",
              "basic":{"first_name":"LAURA","last_name":"O'BRIEN-SMITH","credential":"M.D."},
              "addresses":[
                {"address_purpose":"MAILING","address_1":"PO BOX 1","city":"PLANO","telephone_number":"972-555-0100"},
                {"address_purpose":"LOCATION","address_1":"3500 GASTON AVE","city":"DALLAS","telephone_number":"214-555-0148"}],
              "taxonomies":[{"desc":"Internal Medicine","primary":false},{"desc":"Cardiovascular Disease","primary":true}]}]}
            """;

    @Test
    void aListingUsesTheLocationAddressAndPrimarySpecialty() {
        var d = DoctorSearch.parse(JsonMapper.builder().build().readTree(ONE), "dallas")
                .get(0);
        assertThat(d.name()).isEqualTo("Laura O'Brien-Smith");
        assertThat(d.credential()).isEqualTo("MD");
        assertThat(d.specialty()).isEqualTo("Cardiovascular Disease");
        assertThat(d.address()).isEqualTo("3500 Gaston Ave, Dallas");
        assertThat(d.phone()).isEqualTo("214-555-0148");
    }

    @Test
    void aSearchTheRegistryCantRunHasNoResults() {
        var root = JsonMapper.builder().build().readTree("{\"Errors\":[{\"description\":\"No valid search\"}]}");
        assertThat(DoctorSearch.parse(root, null)).isEmpty();
    }

    @Test
    void theQueryIsLimitedToTexasAndSplitsFullNames() {
        assertThat(List.of(DoctorSearch.query("Dr. Laura Bennett", "Cardio", "Dallas")
                        .split("&")))
                .contains(
                        "state=TX",
                        "first_name=Laura*",
                        "last_name=Bennett*",
                        "taxonomy_description=Cardio*",
                        "city=Dallas");
        assertThat(DoctorSearch.query("Bennett", null, null))
                .contains("last_name=Bennett*")
                .doesNotContain("first_name");
    }

    @Test
    void onlyPeoplePractisingInTheCityAreKeptAndTraineesAreLeftOut() {
        var mapper = JsonMapper.builder().build();
        assertThat(DoctorSearch.parse(mapper.readTree(ONE), "Plano"))
                .as("Plano is only the mailing address")
                .isEmpty();
        assertThat(DoctorSearch.parse(mapper.readTree(ONE), null)).hasSize(1);
        String trainee =
                ONE.replace("Cardiovascular Disease", "Student in an Organized Health Care Education/Training Program");
        assertThat(DoctorSearch.parse(mapper.readTree(trainee), null)).isEmpty();
        String comma = ONE.replace("Cardiovascular Disease", "Speech-Language Pathologist,  ");
        assertThat(DoctorSearch.parse(mapper.readTree(comma), null).get(0).specialty())
                .isEqualTo("Speech-Language Pathologist");
    }

    @Test
    void everydayWordsBecomeTheRegistrysTerms() {
        assertThat(DoctorSearch.query(null, "Cardiology", null))
                .contains("taxonomy_description=Cardiovascular+Disease*");
        assertThat(DoctorSearch.query(null, "Dermatology", null)).contains("taxonomy_description=Dermatology*");
    }
}
