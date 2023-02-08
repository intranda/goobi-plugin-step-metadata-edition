package de.intranda.goobi.plugins;

import java.util.ArrayList;
import java.util.List;

import javax.faces.model.SelectItem;

import org.apache.commons.lang.StringUtils;

import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import ugh.dl.Person;

@Data
@RequiredArgsConstructor
public class MetadataConfiguredField {

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
    private boolean deletable;

    private List<MetadataField> metadataFields = new ArrayList<>();

    private boolean onlyEmptyReadOnlyFields;

    public void addMetadataField(MetadataField mf) {
        this.metadataFields.add(mf);
    }

    public void deleteMetadataField(MetadataField mf) {
        // TODO delete metadata value/Person/Property
        this.metadataFields.remove(mf);
    }

    public boolean isShowField() {
        // show only filled read only fields
        if ("textReadonly".equals(this.type) || "textareaReadonly".equals(this.type) || !this.onlyEmptyReadOnlyFields) {
            for (MetadataField mf : this.metadataFields) {
                if (mf.getPerson()!=null) {
                    Person p = mf.getPerson();
                    if (StringUtils.isNotBlank(p.getLastname()) || StringUtils.isNotBlank(p.getFirstname())) {
                        return true;
                    }
                }
                else if (StringUtils.isNotBlank(mf.getValue())) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

}
