package de.intranda.goobi.plugins;

import javax.faces.model.SelectItem;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.apache.commons.lang.StringUtils;
import org.goobi.beans.Processproperty;

import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import ugh.dl.Metadata;
import ugh.dl.Person;

@Data
@RequiredArgsConstructor
public class MetadataEditionField {

    // holds the actual object
    private Metadata metadata;
    private Person person;
    private Processproperty property;

    @NonNull
    @ToString.Exclude
    private MetadataEditionConfiguredField configuredField;

    public void setVocabularyValue(String value) {
        if (StringUtils.isNotBlank(value)) {
            for (SelectItem item : configuredField.getVocabularyList()) {
                if (value.equals(item.getValue())) {
                    if (metadata != null) {
                        metadata.setValue(item.getLabel());
                        // TODO: Write correct authority information here
//                        metadata.setAuthorityFile(configuredField.getVocabularyName(), configuredField.getVocabularyUrl(),
//                                configuredField.getVocabularyUrl() + "/" + value);
                    } else if (property != null) {
                        property.setWert(item.getLabel());
                    }
                    break;
                }
            }
        } else {
            if (metadata != null) {
                metadata.setValue(null);
            } else if (property != null) {
                property.setWert(null);
            }
        }
    }

    public String getVocabularyValue() {
        String label;
        if (metadata != null) {
            label = metadata.getValue();
        } else if (property != null) {
            label = property.getWert();
        } else {
            label = "";
        }
        if (StringUtils.isNotBlank(label)) {
            return configuredField.getVocabularyList().stream()
                    .filter(s -> label.equals(s.getLabel()))
                    .map(s -> (String) s.getValue())
                    .findFirst()
                    .orElse(null);
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

    public String getHelpText() {
        return configuredField.getHelpText();
    }
}
