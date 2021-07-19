package de.intranda.goobi.plugins;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.faces.context.FacesContext;
import javax.faces.model.SelectItem;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.ResultSetHandler;
import org.apache.commons.lang.StringUtils;
import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.beans.Step;
import org.goobi.production.cli.helper.StringPair;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.flow.statistics.hibernate.FilterHelper;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;
import org.goobi.vocabulary.Field;
import org.goobi.vocabulary.VocabRecord;
import org.goobi.vocabulary.Vocabulary;
import org.primefaces.event.CloseEvent;

import de.intranda.goobi.plugins.ProcessMetadata.ProcessMetadataField;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.FacesContextHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.NIOFileUtils;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.enums.PropertyType;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.metadaten.Image;
import de.sub.goobi.persistence.managers.MetadataManager;
import de.sub.goobi.persistence.managers.MySQLHelper;
import de.sub.goobi.persistence.managers.ProcessManager;
import de.sub.goobi.persistence.managers.PropertyManager;
import de.sub.goobi.persistence.managers.VocabularyManager;
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
    private String title = "intranda_step_metadata_edition";
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

    @Getter
    private boolean hideEmptyFields = true;

    private boolean pagesRTL;
    @Getter
    @Setter
    private Image image = null;

    private int imageIndex = 0;

    @Getter
    private List<MetadataField> metadataFieldList = new ArrayList<>();

    @Getter
    @Setter
    private MetadataField currentField;

    @Getter
    @Setter
    private String searchValue;
    @Getter
    @Setter
    private List<ProcessMetadata> processList;

    private Map<String, WhiteListItem> metadataWhiteListToImport = new LinkedHashMap<>();
    private boolean preselectFields;

    @Getter
    @Setter
    private ProcessMetadata selectedProcess;

    private SubnodeConfiguration myconfig = null;

    @Getter
    @Setter
    private boolean collapsedImageSelection = false;

    @Getter
    @Setter
    private boolean collapsedProperties = false;

    @Getter
    @Setter
    private boolean displaySearchPopup = false;
    @Getter
    @Setter
    private boolean displaySearchOption = false;

    @Getter
    private boolean displayImageArea = true;

    @Getter
    private boolean displayMetadataImportButton = true;

    @Override
    public PluginReturnValue run() {
        return PluginReturnValue.FINISH;
    }

    @Override
    public String cancel() {
        return "/uii" + this.returnPath;
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

        // read parameters from correct block in configuration file
        myconfig = ConfigPlugins.getProjectAndStepConfig(title, step);
        thumbnailSize = myconfig.getInt("thumbnailsize", 200);
        hideEmptyFields = myconfig.getBoolean("hideEmptyFields", true);

        displayImageArea = myconfig.getBoolean("showImages", true);
        displayMetadataImportButton = myconfig.getBoolean("showImportMetadata", true);

        try {
            if ("master".equalsIgnoreCase(myconfig.getString("imageFolder", null))) {
                imageFolderName = process.getImagesOrigDirectory(true);
            } else {
                imageFolderName = process.getImagesTifDirectory(true);
            }
        } catch (IOException | InterruptedException | SwapException | DAOException e3) {
            log.error(e3);
        }
        initDisplayFields(myconfig);
        initSearchFields(myconfig);
        initImageList();
    }

    private void initSearchFields(SubnodeConfiguration config) {
        preselectFields = config.getBoolean("/preselectFields");
        @SuppressWarnings("unchecked")
        List<HierarchicalConfiguration> fieldList = config.configurationsAt("/importfield");
        for (HierarchicalConfiguration field : fieldList) {
            String rulesetName = field.getString("@rulesetName");
            String label = field.getString("@label", rulesetName);
            boolean selectable = field.getBoolean("@selectable", false);
            WhiteListItem wli = new WhiteListItem(rulesetName, label, selectable);
            metadataWhiteListToImport.put(rulesetName, wli);
        }

    }

    private void initDisplayFields(SubnodeConfiguration config) {

        List<Processproperty> properties = process.getEigenschaften();

        metadataFieldList.clear();

        //* Allow multiple properties to be entered and selected as checkboxes, drop down lists and as input text fields
        // get <field> list
        List<HierarchicalConfiguration> fieldList = config.configurationsAt("/displayfield");
        for (HierarchicalConfiguration field : fieldList) {
            // each field has source, name, type, required attributes
            String source = field.getString("@source");
            String name = field.getString("@name");
            String type = field.getString("@type");
            String label = field.getString("@label", name);
            boolean required = field.getBoolean("@required", false);
            String structType = field.getString("@structType", "child");
            String helpText = field.getString("@helpText", "No help text defined.");

            boolean searchable = field.getBoolean("@searchable", false);
            boolean repeatable = field.getBoolean("@repeatable", false);
            String searchSuffix = field.getString("@suffix");

            // each field can have defaultValue, validationRegex, validationErrorText, value (list) sub elements

            String defaultValue = field.getString("/defaultValue", null);
            String validationRegex = field.getString("/validationRegex", null);
            String validationErrorText = field.getString("/validationErrorText", "Value ist invalid");
            List<String> valueList = Arrays.asList(field.getStringArray("/value"));
            String vocabularyName = null;
            String vocabularyUrl = null;
            List<SelectItem> vocabularyRecords = null;
            if (type.equals("vocabularyList")) {
                vocabularyName = field.getString("/vocabularyName");
                List<String> fields = Arrays.asList(field.getStringArray("/searchParameter"));

                if (fields == null) {
                    Vocabulary currentVocabulary = VocabularyManager.getVocabularyByTitle(vocabularyName);
                    vocabularyUrl = getVocabularyBaseName() + currentVocabulary.getId();
                    if (currentVocabulary != null) {
                        VocabularyManager.getAllRecords(currentVocabulary);
                        List<VocabRecord> recordList = currentVocabulary.getRecords();
                        Collections.sort(recordList);
                        vocabularyRecords = new ArrayList<>(recordList.size());
                        if (currentVocabulary != null && currentVocabulary.getId() != null) {
                            for (VocabRecord vr : recordList) {
                                for (Field f : vr.getFields()) {
                                    if (f.getDefinition().isMainEntry()) {
                                        vocabularyRecords.add(new SelectItem(String.valueOf(vr.getId()), f.getValue()));
                                        break;
                                    }
                                }
                            }
                        }
                    }
                } else {
                    List<StringPair> vocabularySearchFields = new ArrayList<>();
                    for (String fieldname : fields) {
                        String[] parts = fieldname.trim().split("=");
                        if (parts.length > 1) {
                            String fieldName = parts[0];
                            String value = parts[1];
                            StringPair sp = new StringPair(fieldName, value);
                            vocabularySearchFields.add(sp);
                        }
                    }
                    List<VocabRecord> records = VocabularyManager.findRecords(vocabularyName, vocabularySearchFields);
                    if (records != null && records.size() > 0) {
                        Collections.sort(records);
                        vocabularyRecords = new ArrayList<>(records.size());
                        for (VocabRecord vr : records) {

                            if (StringUtils.isBlank(vocabularyUrl)) {
                                vocabularyUrl = getVocabularyBaseName() + vr.getVocabularyId();
                            }

                            for (Field f : vr.getFields()) {
                                if (f.getDefinition().isMainEntry()) {
                                    vocabularyRecords.add(new SelectItem(String.valueOf(vr.getId()), f.getValue()));
                                    break;
                                }
                            }
                        }
                    }
                }
            }

            boolean found = false;
            if ("property".contains(source)) {
                for (Processproperty prop : properties) {
                    if (prop.getTitel().equals(name)) {
                        MetadataField metadataField = new MetadataField(source, name, type, label, required, helpText, searchable);
                        metadataField.setValidationRegex(validationRegex);
                        metadataField.setValidationErrorText(validationErrorText);
                        metadataField.setValueList(valueList);
                        metadataField.setProperty(prop);
                        metadataField.setSearchSuffix(searchSuffix);
                        metadataField.setVocabularyList(vocabularyRecords);
                        metadataField.setVocabularyName(vocabularyName);
                        metadataField.setVocabularyUrl(vocabularyUrl);
                        metadataField.setRepeatable(repeatable);
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
                    MetadataField metadataField = new MetadataField(source, name, type, label, required, helpText, searchable);
                    metadataField.setValidationRegex(validationRegex);
                    metadataField.setValidationErrorText(validationErrorText);
                    metadataField.setValueList(valueList);
                    metadataField.setProperty(property);
                    metadataField.setSearchSuffix(searchSuffix);
                    metadataField.setVocabularyList(vocabularyRecords);
                    metadataField.setVocabularyName(vocabularyName);
                    metadataField.setVocabularyUrl(vocabularyUrl);
                    metadataField.setRepeatable(repeatable);
                    metadataFieldList.add(metadataField);
                }
            } else if ("metadata".contains(source)) {
                List<Metadata> metadataList;
                if (anchor != null && structType.equals("anchor")) {
                    metadataList = anchor.getAllMetadata();
                } else {
                    metadataList = logical.getAllMetadata();
                }

                if (metadataList != null) {
                    for (Metadata md : metadataList) {
                        if (md.getType().getName().equals(name)) {
                            MetadataField metadataField = new MetadataField(source, name, type, label, required, helpText, searchable);
                            metadataField.setValidationRegex(validationRegex);
                            metadataField.setValidationErrorText(validationErrorText);
                            metadataField.setValueList(valueList);
                            metadataField.setMetadata(md);
                            metadataField.setSearchSuffix(searchSuffix);
                            metadataField.setVocabularyList(vocabularyRecords);
                            metadataField.setVocabularyName(vocabularyName);
                            metadataField.setVocabularyUrl(vocabularyUrl);
                            metadataField.setRepeatable(repeatable);
                            if (StringUtils.isBlank(md.getValue())) {
                                md.setValue(defaultValue);
                            }
                            found = true;
                            metadataFieldList.add(metadataField);
                        }
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
                        MetadataField metadataField = new MetadataField(source, name, type, label, required, helpText, searchable);
                        metadataField.setValidationRegex(validationRegex);
                        metadataField.setValidationErrorText(validationErrorText);
                        metadataField.setValueList(valueList);
                        metadataField.setMetadata(md);
                        metadataField.setSearchSuffix(searchSuffix);
                        metadataField.setVocabularyList(vocabularyRecords);
                        metadataField.setVocabularyName(vocabularyName);
                        metadataField.setVocabularyUrl(vocabularyUrl);
                        metadataField.setRepeatable(repeatable);
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

                if (personList != null) {
                    for (Person p : personList) {
                        if (p.getType().getName().equals(name)) {
                            MetadataField metadataField = new MetadataField(source, name, type, label, required, helpText, searchable);
                            metadataField.setValidationRegex(validationRegex);
                            metadataField.setValidationErrorText(validationErrorText);
                            metadataField.setValueList(valueList);
                            metadataField.setPerson(p);
                            metadataField.setSearchSuffix(searchSuffix);
                            metadataField.setVocabularyList(vocabularyRecords);
                            metadataField.setVocabularyName(vocabularyName);
                            metadataField.setVocabularyUrl(vocabularyUrl);
                            found = true;
                            metadataFieldList.add(metadataField);
                        }
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
                        MetadataField metadataField = new MetadataField(source, name, type, label, required, helpText, searchable);
                        metadataField.setValidationRegex(validationRegex);
                        metadataField.setValidationErrorText(validationErrorText);
                        metadataField.setValueList(valueList);
                        metadataField.setPerson(person);
                        metadataField.setSearchSuffix(searchSuffix);
                        metadataField.setVocabularyList(vocabularyRecords);
                        metadataField.setVocabularyName(vocabularyName);
                        metadataField.setVocabularyUrl(vocabularyUrl);
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

    // import metadata from other process
    public void importMetadataFromExternalProcess() {
        if (selectedProcess == null) {
            return;
        }
        // load metadata of the process
        Process other = ProcessManager.getProcessById(selectedProcess.getProcessId());
        DocStruct otherLogical = null;
        DocStruct otherAnchor = null;
        try {
            Fileformat otherFormat = other.readMetadataFile();

            otherLogical = otherFormat.getDigitalDocument().getLogicalDocStruct();
            if (otherLogical.getType().isAnchor()) {
                otherAnchor = otherLogical;
                otherLogical = otherLogical.getAllChildren().get(0);
            }

        } catch (ReadException | PreferencesException | WriteException | IOException | InterruptedException | SwapException | DAOException e) {
            log.error(e);
        }

        // get list of metadata types
        List<String> metadataTypesToDelete = new ArrayList<>();
        for (ProcessMetadataField pmf : selectedProcess.getMetadataFieldList()) {
            if (pmf.isSelected() && !metadataTypesToDelete.contains(pmf.getMetadataName())) {
                metadataTypesToDelete.add(pmf.getMetadataName());
            }
        }
        // remove existing types from current element
        for (String metadataType : metadataTypesToDelete) {
            removeMetadata(metadataType, logical);
        }
        if (anchor != null) {
            for (String metadataType : metadataTypesToDelete) {
                removeMetadata(metadataType, anchor);
            }
        }

        // add new metadata
        for (ProcessMetadataField pmf : selectedProcess.getMetadataFieldList()) {
            if (pmf.isSelected()) {
                importSelectedMetadata(otherLogical, pmf, logical);
            }
            if (anchor != null && otherAnchor != null) {
                importSelectedMetadata(otherAnchor, pmf, anchor);
            }
        }

        // update metadataFieldList
        initDisplayFields(myconfig);
    }

    private void importSelectedMetadata(DocStruct source, ProcessMetadataField pmf, DocStruct destination) {
        MetadataType mdt = prefs.getMetadataTypeByName(pmf.getMetadataName());
        if (mdt.getIsPerson()) {
            List<Person> personList = source.getAllPersonsByType(mdt);
            Person personToImport = null;
            if (personList != null) {
                for (Person person : personList) {
                    if (StringUtils.isNotBlank(person.getFirstname()) && StringUtils.isNotBlank(person.getLastname())) {
                        if (pmf.getValue().equals(person.getFirstname() + " " + person.getLastname())) {
                            personToImport = person;
                            break;
                        }
                    } else if (StringUtils.isNotBlank(person.getFirstname()) && pmf.getValue().equals(person.getFirstname())) {
                        personToImport = person;
                        break;
                    } else if (pmf.getValue().equals(person.getLastname())) {
                        personToImport = person;
                        break;
                    }
                }
            }
            if (personToImport != null) {
                try {
                    Person p = new Person(mdt);
                    p.setFirstname(personToImport.getFirstname());
                    p.setLastname(personToImport.getLastname());
                    p.setAutorityFile(personToImport.getAuthorityID(), personToImport.getAuthorityURI(), personToImport.getAuthorityValue());
                    destination.addPerson(p);
                } catch (MetadataTypeNotAllowedException e) {
                    log.error(e);
                }
            }
        } else {
            List<? extends Metadata> metadataList = source.getAllMetadataByType(mdt);
            Metadata metadataToImport = null;
            if (metadataList != null) {
                for (Metadata metadata : metadataList) {
                    if (pmf.getValue().equals(metadata.getValue())) {
                        metadataToImport = metadata;
                        break;
                    }

                }
            }
            if (metadataToImport != null) {
                try {
                    Metadata md = new Metadata(mdt);
                    md.setValue(metadataToImport.getValue());
                    md.setAutorityFile(metadataToImport.getAuthorityID(), metadataToImport.getAuthorityURI(), metadataToImport.getAuthorityValue());
                    destination.addMetadata(md);
                } catch (MetadataTypeNotAllowedException e) {
                    log.error(e);
                }
            }
        }
        displaySearchPopup = false;
    }

    private void removeMetadata(String metadataType, DocStruct docstruct) {
        List<? extends Metadata> mdl = docstruct.getAllMetadataByType(prefs.getMetadataTypeByName(metadataType));
        if (mdl != null) {
            for (Metadata md : mdl) {
                docstruct.removeMetadata(md);
            }
        }
        List<Person> personList = docstruct.getAllPersonsByType(prefs.getMetadataTypeByName(metadataType));
        if (personList != null) {
            for (Person p : personList) {
                docstruct.removePerson(p);
            }
        }
    }

    public void saveAllChanges() {

        boolean valid = true;
        for (MetadataField mf : metadataFieldList) {
            if (mf.getRequired().booleanValue() && StringUtils.isBlank(mf.getValue())) {
                Helper.setFehlerMeldung(mf.getLabel() + ": " + Helper.getTranslation("valueIsRequired"), "");
                valid = false;
            } else if (StringUtils.isNotBlank(mf.getValidationRegex()) && !mf.getValue().matches(mf.getValidationRegex())) {
                Helper.setFehlerMeldung(mf.getLabel() + ": " + mf.getValidationErrorText(), "");
                valid = false;
            }
        }

        if (!valid) {
            return;
        }

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
        String sql = FilterHelper.criteriaBuilder(searchValue + " -id:" + process.getId(), false, null, null, null, true, false);
        sql = sql + " and prozesse.istTemplate = false ";
        Map<Integer, String> foundProcessIds = getAllProcessesWithMetadata(sql);

        processList = new ArrayList<>(foundProcessIds.size());

        for (Integer id : foundProcessIds.keySet()) {
            List<StringPair> metadataList = MetadataManager.getMetadata(id);
            ProcessMetadata pm = new ProcessMetadata(id, foundProcessIds.get(id), metadataList, metadataWhiteListToImport, preselectFields);
            processList.add(pm);
        }
    }

    // get process id and title of all processes with a given metadata

    private static Map<Integer, String> getAllProcessesWithMetadata(String filter) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT prozesse.prozesseID, prozesse.titel FROM prozesse use index (status) left join batches on prozesse.batchId = batches.id ");
        sql.append("left join projekte on prozesse.ProjekteID = projekte.ProjekteID ");
        sql.append("left join institution on projekte.institution_id = institution.id ");
        sql.append(" WHERE " + filter);

        Connection connection = null;
        try {
            connection = MySQLHelper.getInstance().getConnection();
            return new QueryRunner().query(connection, sql.toString(), resultSetToMapHandler);

        } catch (SQLException e) {
            log.error(e);
        } finally {
            if (connection != null) {
                try {
                    MySQLHelper.closeConnection(connection);
                } catch (SQLException e) {
                    log.error(e);
                }
            }
        }
        return Collections.emptyMap();
    }

    public static ResultSetHandler<Map<Integer, String>> resultSetToMapHandler = new ResultSetHandler<Map<Integer, String>>() {
        @Override
        public Map<Integer, String> handle(ResultSet rs) throws SQLException {
            Map<Integer, String> answer = new HashMap<>();
            try {
                while (rs.next()) {
                    Integer processid = rs.getInt("prozesseID");
                    String processTitle = rs.getString("titel");
                    answer.put(processid, processTitle);
                }
            } finally {
                if (rs != null) {
                    rs.close();
                }
            }
            return answer;
        }
    };

    @AllArgsConstructor
    @Getter
    public class WhiteListItem {
        private String rulesetName;
        private String label;
        private boolean selectable;
    }

    public boolean getProcessListIsEmpty() {
        return processList == null || processList.isEmpty();
    }

    public void seachField() {

        StringBuilder searchQuery = new StringBuilder();

        switch (currentField.getSource()) {
            case "property":
                // "processproperty:name:value"
                searchQuery.append("\"processproperty:");
                searchQuery.append(currentField.getName());
                searchQuery.append(":");
                searchQuery.append(currentField.getValue());
                searchQuery.append("\" ");
                break;
            case "metadata":
                // "meta:name:value"
                searchQuery.append("\"meta:");
                searchQuery.append(currentField.getName());
                searchQuery.append(":");
                searchQuery.append(currentField.getValue());
                searchQuery.append("\" ");
                break;
            case "person":
                // "meta:name:firstname lastname"
                String personName = null;
                if (StringUtils.isNotBlank(currentField.getPerson().getFirstname())
                        && StringUtils.isNotBlank(currentField.getPerson().getLastname())) {
                    personName = currentField.getPerson().getFirstname() + " " + currentField.getPerson().getLastname();
                } else if (StringUtils.isNotBlank(currentField.getPerson().getFirstname())) {
                    personName = currentField.getPerson().getFirstname();
                } else {
                    personName = currentField.getPerson().getLastname();
                }
                searchQuery.append("\"meta:");
                searchQuery.append(currentField.getName());
                searchQuery.append(":");
                searchQuery.append(personName);
                searchQuery.append("\" ");

                break;
        }
        // addd configured suffix
        String suffix = currentField.getSearchSuffix();
        if (StringUtils.isNotBlank(suffix)) {
            searchQuery.append(suffix);
        }
        // exclude current process id
        searchQuery.append(" -id:").append(process.getId());

        String sql = FilterHelper.criteriaBuilder(searchQuery.toString(), false, null, null, null, true, false);
        sql = sql + " and prozesse.istTemplate = false ";
        Map<Integer, String> foundProcessIds = getAllProcessesWithMetadata(sql);

        processList = new ArrayList<>(foundProcessIds.size());

        for (Integer id : foundProcessIds.keySet()) {
            List<StringPair> metadataList = MetadataManager.getMetadata(id);
            ProcessMetadata pm = new ProcessMetadata(id, foundProcessIds.get(id), metadataList, metadataWhiteListToImport, preselectFields);
            processList.add(pm);
        }
        displaySearchOption = false;
        displaySearchPopup = true;
    }

    public void handleClose(CloseEvent event) {
        displaySearchPopup = false;
    }

    public void openPopup() {
        processList = null;
        displaySearchOption = true;
        displaySearchPopup = true;
    }

    private String getVocabularyBaseName() {
        FacesContext context = FacesContextHelper.getCurrentFacesContext();
        HttpServletRequest request = (HttpServletRequest) context.getExternalContext().getRequest();
        String contextPath = request.getContextPath();
        String scheme = request.getScheme(); // http
        String serverName = request.getServerName(); // hostname.com
        int serverPort = request.getServerPort(); // 80
        String reqUrl = scheme + "://" + serverName + ":" + serverPort + contextPath;
        Client client = ClientBuilder.newClient();
        WebTarget base = client.target(reqUrl);
        WebTarget vocabularyBase = base.path("api").path("vocabulary");
        return vocabularyBase.path("records").getUri().toString() + "/";
    }

    public void duplicateField() {
        if (currentField != null) {
            MetadataField metadataField = new MetadataField(currentField.getSource(), currentField.getName(), currentField.getType(),
                    currentField.getLabel(), currentField.getRequired(), currentField.getHelpText(), currentField.getSearchable());
            metadataField.setValidationRegex(currentField.getValidationRegex());
            metadataField.setValidationErrorText(currentField.getValidationErrorText());
            metadataField.setValueList(currentField.getValueList());
            metadataField.setSearchSuffix(currentField.getSearchSuffix());
            metadataField.setVocabularyList(currentField.getVocabularyList());
            metadataField.setVocabularyName(currentField.getVocabularyName());
            metadataField.setVocabularyUrl(currentField.getVocabularyUrl());
            metadataField.setRepeatable(currentField.isRepeatable());
            metadataFieldList.add(metadataField);
            if ("property".contains(currentField.getSource())) {
                Processproperty property = new Processproperty();
                property.setContainer(0);
                property.setCreationDate(new Date());
                property.setProcessId(process.getId());
                property.setProzess(process);
                property.setTitel(currentField.getName());
                property.setType(PropertyType.String);
                property.setWert(currentField.getProperty().getWert());
                process.getEigenschaften().add(property);
                metadataField.setProperty(property);

            } else if ("metadata".contains(currentField.getSource())) {
                try {
                    Metadata md = new Metadata(currentField.getMetadata().getType());
                    md.setValue(currentField.getMetadata().getValue());
                    currentField.getMetadata().getParent().addMetadata(md);
                    metadataField.setMetadata(md);
                } catch (MetadataTypeNotAllowedException e) {
                    log.error(e);
                }

            } else {
                try {
                    Person person = new Person(currentField.getPerson().getType());
                    person.setFirstname(currentField.getPerson().getFirstname());
                    person.setLastname(currentField.getPerson().getLastname());
                    person.setAutorityFile(currentField.getPerson().getAuthorityID(), currentField.getPerson().getAuthorityURI(),
                            currentField.getPerson().getAuthorityValue());
                    currentField.getPerson().getParent().addPerson(person);
                    metadataField.setPerson(person);

                } catch (MetadataTypeNotAllowedException e) {
                    log.error(e);
                }
            }
        }
    }

}
