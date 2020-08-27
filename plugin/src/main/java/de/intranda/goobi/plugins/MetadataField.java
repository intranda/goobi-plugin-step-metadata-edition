package de.intranda.goobi.plugins;

import java.util.List;

import org.goobi.beans.Processproperty;

import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import ugh.dl.Metadata;
import ugh.dl.Person;

@Data
@RequiredArgsConstructor
public class MetadataField {

    // metadata, property or person
    @NonNull
    private String source;

    // name of the field
    @NonNull
    private String name;

    // display type, can be text, list, checkbox, multivalue (person)
    @NonNull
    private String type;

    // label to display, defaults to the name of the the field
    @NonNull
    private String label;

    // mandatory or optional
    @NonNull
    private Boolean required;
    
    // text to show as help text
    @NonNull
    private String helpText;

    // define if the field is taken from anchor or child element, relevant for multipart volumes, periodca, can be 'anchor' or 'child'
    private String structType = "child";

    // holds the actual object
    private Metadata metadata;
    private Person person;
    private Processproperty property;

    // default value, if the field is empty
    private String defaultValue;

    // regular expression to validate the value
    private String validationRegex;

    // text to display on validation errors
    private String validationErrorText;
    
    // list of possible values for type="list"
    private List<String> valueList;

    @NonNull
    private Boolean searchable;

    private String searchSuffix;

    public void setValue(String value) {
        if (metadata != null) {
            metadata.setValue(value);
        } else if (property != null) {
            property.setWert(value);
        }
    }

    public String getValue() {
        if (metadata != null) {
            return metadata.getValue();
        } else if (property != null) {
            return property.getWert();
        }
        return null;
    }

}
