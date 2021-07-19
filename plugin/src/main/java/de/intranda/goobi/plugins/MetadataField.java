package de.intranda.goobi.plugins;

import java.util.List;

import javax.faces.model.SelectItem;

import org.apache.commons.lang.StringUtils;
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

    // define if the field is taken from anchor or child element, relevant for multipart volumes, periodica, can be 'anchor' or 'child'
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

    private String vocabularyName;
    private String vocabularyUrl;
    private List<SelectItem> vocabularyList;

    private boolean repeatable;

    public void setVocabularyValue(String value) {
        if (StringUtils.isNotBlank(value)) {
            for (SelectItem item : vocabularyList) {
                if (value.equals(item.getValue())) {
                    if (metadata != null) {
                        metadata.setValue(item.getLabel());
                        metadata.setAutorityFile(vocabularyName, vocabularyUrl,
                                vocabularyUrl + "/" + value);
                    } else if (property != null) {
                        property.setWert(item.getLabel());
                    }
                    break;
                }
            }
        }
    }

    public String getVocabularyValue() {
        String label = "";
        if (metadata != null) {
            label = metadata.getValue();
        } else if (property != null) {
            label = property.getWert();
        }
        if (StringUtils.isNotBlank(label)) {
            for (SelectItem item : vocabularyList) {
                if (label.equals(item.getLabel())) {
                    return (String) item.getValue();
                }
            }
        }
        return null;
    }

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
