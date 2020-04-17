package de.intranda.goobi.plugins;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.goobi.production.cli.helper.StringPair;

import de.intranda.goobi.plugins.MetadataEditionPlugin.WhiteListItem;
import lombok.Data;
import lombok.Getter;

public class ProcessMetadata {
    @Getter
    private Integer processId;
    @Getter
    private List<ProcessMetadataField> metadataFieldList = new ArrayList<>();

    /**
     * 
     * @param metadataList list of existing metadata, first field contains the internal name, second field the value
     * @param whitelistedFieldNames map of white list elements, key is the ruleset name, value a label to display
     */

    public ProcessMetadata(Integer processid, List<StringPair> metadataList, Map<String, WhiteListItem> whitelistedFieldNames) {
        this.processId = processid;
        for (StringPair metadata : metadataList) {
            if (whitelistedFieldNames.containsKey(metadata.getOne())) {
                WhiteListItem wli =  whitelistedFieldNames.get(metadata.getOne());

                ProcessMetadataField pmf = new ProcessMetadataField();
                pmf.setMetadataName(metadata.getOne());
                pmf.setValue(metadata.getTwo());
                pmf.setLabel(wli.getLabel());


                pmf.setWhitelisted(wli.isSelectable());
                metadataFieldList.add(pmf);
            }
        }

    }

    @Data
    public class ProcessMetadataField {

        private boolean selected;
        private boolean whitelisted;
        private String metadataName;
        private String label;
        private String value;

    }}
