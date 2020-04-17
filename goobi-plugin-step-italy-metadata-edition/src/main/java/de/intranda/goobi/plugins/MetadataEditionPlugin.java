package de.intranda.goobi.plugins;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.lang.StringUtils;
import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.beans.Step;
import org.goobi.production.cli.helper.StringPair;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.NIOFileUtils;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.enums.PropertyType;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.metadaten.Image;
import de.sub.goobi.persistence.managers.MetadataManager;
import de.sub.goobi.persistence.managers.PropertyManager;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Person;
import ugh.dl.Prefs;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.WriteException;

@Log4j2
@PluginImplementation
public class MetadataEditionPlugin implements IStepPluginVersion2 {

    /*
    Develop a new Goobi step plugin to allow multiple functionalities
     * Show the images of the process within the Part GUI
     * Allow the selection of a representative image
     * Allow multiple properties to be entered and selected as checkboxes, drop down lists and as input text fields
     * Allow the search of other processes inside of Goobi to find items with the same NLI identifier and which have a specific workflow progress
     * Allow to duplicate metadata from a searched Goobi process into the current process
     * Create and publish a documentation for this plugin
     */

    @Getter
    private Step step;
    private String returnPath;

    @Getter
    private String title = "intranda_step_metadata-edition";
    @Getter
    private PluginType type = PluginType.Step;

    @Getter
    private PluginGuiType pluginGuiType = PluginGuiType.PART;

    private Process process;
    private String imageFolderName;

    // metadata
    private Prefs prefs;
    private Fileformat fileformat;
    private DigitalDocument digitalDocument;
    private DocStruct logical;
    private DocStruct anchor;
    private DocStruct physical;

    @Getter
    private List<Image> allImages = new ArrayList<>();

    @Getter
    private int thumbnailSize = 200;

    private boolean pagesRTL;
    @Getter
    @Setter
    private Image image = null;

    private int imageIndex = 0;

    @Getter
    private List<MetadataField> metadataFieldList = new ArrayList<>();

    private String searchField;

    @Getter
    @Setter
    private String searchValue;
    @Getter
    @Setter
    private List<ProcessMetadata> processList;

    private Map<String, WhiteListItem> metadataWhiteListToImport = new HashMap<>();

    @Override
    public PluginReturnValue run() {
        return PluginReturnValue.FINISH;
    }

    @Override
    public String cancel() {
        return returnPath;
    }

    @Override
    public boolean execute() {
        return true;
    }

    @Override
    public String finish() {
        return returnPath;
    }

    @Override
    public String getPagePath() {
        return null;
    }

    @Override
    public void initialize(Step step, String returnPath) {
        this.step = step;
        this.returnPath = returnPath;

        process = step.getProzess();

        try {
            imageFolderName = process.getImagesTifDirectory(true);
        } catch (IOException | InterruptedException | SwapException | DAOException e3) {
            log.error(e3);
        }

        prefs = process.getRegelsatz().getPreferences();
        try {
            fileformat = process.readMetadataFile();
            digitalDocument = fileformat.getDigitalDocument();
            logical = digitalDocument.getLogicalDocStruct();
            if (logical.getType().isAnchor()) {
                anchor = logical;
                logical = logical.getAllChildren().get(0);
            }
            physical = digitalDocument.getPhysicalDocStruct();
        } catch (ReadException | PreferencesException | WriteException | IOException | InterruptedException | SwapException | DAOException e1) {
            log.error(e1);
        }

        List<Metadata> lstMetadata = physical.getAllMetadata();
        for (Metadata md : lstMetadata) {
            if (md.getType().getName().equals("_representative")) {
                try {
                    Integer value = new Integer(md.getValue());
                    if (value > 0) {
                        imageIndex = value - 1;
                    }
                } catch (Exception e) {
                    log.error(e);
                }
            }
        }

        if (logical.getAllMetadata() != null) {
            for (Metadata md : logical.getAllMetadata()) {
                if (md.getType().getName().equals("_directionRTL")) {
                    try {
                        pagesRTL = Boolean.valueOf(md.getValue());
                    } catch (Exception e) {
                    }
                }
            }
        }

        String projectName = step.getProzess().getProjekt().getTitel();

        XMLConfiguration xmlConfig = ConfigPlugins.getPluginConfig(getTitle());
        xmlConfig.setExpressionEngine(new XPathExpressionEngine());
        xmlConfig.setReloadingStrategy(new FileChangedReloadingStrategy());

        SubnodeConfiguration myconfig = null;

        // order of configuration is:
        // 1.) project name and step name matches
        // 2.) step name matches and project is *
        // 3.) project name matches and step name is *
        // 4.) project name and step name are *
        try {
            myconfig = xmlConfig.configurationAt("//config[./project = '" + projectName + "'][./step = '" + step.getTitel() + "']");
        } catch (IllegalArgumentException e) {
            try {
                myconfig = xmlConfig.configurationAt("//config[./project = '*'][./step = '" + step.getTitel() + "']");
            } catch (IllegalArgumentException e1) {
                try {
                    myconfig = xmlConfig.configurationAt("//config[./project = '" + projectName + "'][./step = '*']");
                } catch (IllegalArgumentException e2) {
                    myconfig = xmlConfig.configurationAt("//config[./project = '*'][./step = '*']");
                }
            }
        }
        thumbnailSize = myconfig.getInt("thumbnailsize", 200);

        initDisplayFields(myconfig);

        initSearchFields(myconfig);

        initImageList();
    }

    private void initSearchFields(SubnodeConfiguration config) {
        searchField = config.getString("searchfield/@rulesetName");

        @SuppressWarnings("unchecked")
        List<SubnodeConfiguration> fieldList = config.configurationsAt("/importfield");
        for (SubnodeConfiguration field : fieldList) {
            String rulesetName = field.getString("@rulesetName");
            String label = field.getString("@label", rulesetName);
            boolean selectable = field.getBoolean("@selectable", false);
            WhiteListItem wli = new WhiteListItem(rulesetName, label, selectable);
            metadataWhiteListToImport.put(rulesetName, wli);
        }
    }

    private void initDisplayFields(SubnodeConfiguration config) {

        List<Processproperty> properties = process.getEigenschaften();

        // size of thumbnails

        //* Allow multiple properties to be entered and selected as checkboxes, drop down lists and as input text fields
        // get <field> list
        @SuppressWarnings("unchecked")
        List<SubnodeConfiguration> fieldList = config.configurationsAt("/displayfield");
        for (SubnodeConfiguration field : fieldList) {
            // each field has source, name, type, required attributes
            String source = field.getString("@source");
            String name = field.getString("@name");
            String type = field.getString("@type");
            String label = field.getString("@label", name);
            boolean required = field.getBoolean("@required", false);
            String structType = field.getString("@structType", "child");

            // each field can have defaultValue, validationRegex, validationErrorText, value (list) sub elements

            String defaultValue = field.getString("/defaultValue", null);
            String validationRegex = field.getString("/validationRegex", null);
            String validationErrorText = field.getString("/validationErrorText", "Value ist invalid");
            @SuppressWarnings("unchecked")
            List<String> valueList = field.getList("/value", null);

            boolean found = false;
            if ("property".contains(source)) {
                for (Processproperty prop : properties) {
                    if (prop.getTitel().equals(name)) {
                        MetadataField metadataField = new MetadataField(source, name, type, label, required);
                        metadataField.setValidationRegex(validationRegex);
                        metadataField.setValidationErrorText(validationErrorText);
                        metadataField.setValueList(valueList);
                        metadataField.setProperty(prop);
                        if (StringUtils.isBlank(prop.getWert())) {
                            prop.setWert(defaultValue);
                        }
                        found = true;
                        metadataFieldList.add(metadataField);
                    }
                }
                if (!found) {
                    Processproperty property = new Processproperty();
                    property.setContainer(0);
                    property.setCreationDate(new Date());
                    property.setProcessId(process.getId());
                    property.setProzess(process);
                    property.setTitel(name);
                    property.setType(PropertyType.String);
                    property.setWert(defaultValue);
                    process.getEigenschaften().add(property);
                    MetadataField metadataField = new MetadataField(source, name, type, label, required);
                    metadataField.setValidationRegex(validationRegex);
                    metadataField.setValidationErrorText(validationErrorText);
                    metadataField.setValueList(valueList);
                    metadataField.setProperty(property);
                    metadataFieldList.add(metadataField);
                }
            } else if ("metadata".contains(source)) {
                List<Metadata> metadataList;
                if (anchor != null && structType.equals("anchor")) {
                    metadataList = anchor.getAllMetadata();
                } else {
                    metadataList = logical.getAllMetadata();
                }
                for (Metadata md : metadataList) {
                    if (md.getType().getName().equals(name)) {
                        MetadataField metadataField = new MetadataField(source, name, type, label, required);
                        metadataField.setValidationRegex(validationRegex);
                        metadataField.setValidationErrorText(validationErrorText);
                        metadataField.setValueList(valueList);
                        metadataField.setMetadata(md);
                        if (StringUtils.isBlank(md.getValue())) {
                            md.setValue(defaultValue);
                        }
                        found = true;
                        metadataFieldList.add(metadataField);
                    }
                }
                if (!found) {
                    try {
                        Metadata md = new Metadata(prefs.getMetadataTypeByName(name));
                        md.setValue(defaultValue);
                        if (anchor != null && structType.equals("anchor")) {
                            anchor.addMetadata(md);
                        } else {
                            logical.addMetadata(md);
                        }
                        MetadataField metadataField = new MetadataField(source, name, type, label, required);
                        metadataField.setValidationRegex(validationRegex);
                        metadataField.setValidationErrorText(validationErrorText);
                        metadataField.setValueList(valueList);
                        metadataField.setMetadata(md);
                        metadataFieldList.add(metadataField);
                    } catch (MetadataTypeNotAllowedException e) {
                        log.error(e);
                    }
                }

            } else {
                // person
                List<Person> personList;
                if (anchor != null && structType.equals("anchor")) {
                    personList = anchor.getAllPersons();
                } else {
                    personList = logical.getAllPersons();
                }
                for (Person p : personList) {
                    if (p.getType().getName().equals(name)) {
                        MetadataField metadataField = new MetadataField(source, name, type, label, required);
                        metadataField.setValidationRegex(validationRegex);
                        metadataField.setValidationErrorText(validationErrorText);
                        metadataField.setValueList(valueList);
                        metadataField.setPerson(p);
                        found = true;
                        metadataFieldList.add(metadataField);
                    }
                }
                if (!found) {
                    try {
                        Person person = new Person(prefs.getMetadataTypeByName(name));
                        if (anchor != null && structType.equals("anchor")) {
                            anchor.addPerson(person);
                        } else {
                            logical.addPerson(person);
                        }
                        MetadataField metadataField = new MetadataField(source, name, type, label, required);
                        metadataField.setValidationRegex(validationRegex);
                        metadataField.setValidationErrorText(validationErrorText);
                        metadataField.setValueList(valueList);
                        metadataField.setPerson(person);
                    } catch (MetadataTypeNotAllowedException e) {
                        log.error(e);
                    }
                }
            }
        }

        //* Allow the search of other processes inside of Goobi to find items with the same NLI identifier and which have a specific workflow progress
        //* Allow to duplicate metadata from a searched Goobi process into the current process

    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null;
    }

    @Override
    public int getInterfaceVersion() {
        return 0;
    }

    private void initImageList() {
        Path path = Paths.get(imageFolderName);
        if (StorageProvider.getInstance().isFileExists(path)) {
            List<String> imageNameList = StorageProvider.getInstance().list(imageFolderName, NIOFileUtils.imageOrObjectNameFilter);
            int order = 1;
            for (String imagename : imageNameList) {

                try {
                    Image currentImage = new Image(process, imageFolderName, imagename, order, thumbnailSize);

                    allImages.add(currentImage);
                    order++;
                } catch (IOException | InterruptedException | SwapException | DAOException e) {
                    log.error("Error initializing image " + imagename, e);
                }
            }
        }
        setImageIndex(imageIndex);
    }

    public int getSizeOfImageList() {
        return allImages.size();
    }

    public String getFlowDir() {
        return this.pagesRTL ? "rtl" : "ltr";
    }

    public void setImageIndex(int imageIndex) {
        this.imageIndex = imageIndex;
        if (this.imageIndex < 0) {
            this.imageIndex = 0;
        }
        if (this.imageIndex >= getSizeOfImageList()) {
            this.imageIndex = getSizeOfImageList() - 1;
        }
        if (this.imageIndex >= 0) {
            setImage(allImages.get(this.imageIndex));
        }
    }

    public void saveAllChanges() {

        // save properties
        for (MetadataField mf : metadataFieldList) {
            if (mf.getProperty() != null) {
                PropertyManager.saveProcessProperty(mf.getProperty());
            }
        }
        // save reading direction
        if (logical.getAllMetadata() != null) {
            boolean match = false;
            for (Metadata md : logical.getAllMetadata()) {
                if (md.getType().getName().equals("_directionRTL")) {
                    md.setValue(String.valueOf(this.pagesRTL));
                    match = true;
                }
            }
            if (!match) {
                MetadataType mdt = prefs.getMetadataTypeByName("_directionRTL");
                if (mdt != null) {
                    try {
                        Metadata md = new Metadata(mdt);
                        md.setValue(String.valueOf(this.pagesRTL));
                        logical.addMetadata(md);
                    } catch (MetadataTypeNotAllowedException e) {

                    }
                }
            }
        }
        // save representative image
        boolean match = false;
        if (physical != null && physical.getAllMetadata() != null && physical.getAllMetadata().size() > 0) {
            for (Metadata md : physical.getAllMetadata()) {
                if (md.getType().getName().equals("_representative")) {
                    md.setValue(String.valueOf(imageIndex + 1));
                    match = true;
                }
            }
        }
        if (!match) {
            MetadataType mdt = prefs.getMetadataTypeByName("_representative");
            try {
                Metadata md = new Metadata(mdt);
                Integer value = new Integer(imageIndex + 1);
                md.setValue(String.valueOf(value));

                physical.addMetadata(md);
            } catch (MetadataTypeNotAllowedException e) {
            }
        }

        // save mets file

        try {
            process.writeMetadataFile(fileformat);
        } catch (WriteException | PreferencesException | IOException | InterruptedException | SwapException | DAOException e) {
            log.error(e);
        }
    }

    public void searchForMetadata() {

        List<Integer> foundProcessIds = MetadataManager.getProcessesWithMetadata(searchField, searchValue);

        processList = new ArrayList<>(foundProcessIds.size());

        for (Integer id : foundProcessIds) {
            List<StringPair> metadataList = MetadataManager.getMetadata(id);
            ProcessMetadata pm = new ProcessMetadata(id, metadataList, metadataWhiteListToImport);
            processList.add(pm);
        }

    }

    @AllArgsConstructor
    @Getter
    public class WhiteListItem {
        private String rulesetName;
        private String label;
        private boolean selectable;


    }

}
