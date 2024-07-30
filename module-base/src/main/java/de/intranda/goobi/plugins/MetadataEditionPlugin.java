package de.intranda.goobi.plugins;

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
import io.goobi.vocabulary.exchange.FieldDefinition;
import io.goobi.vocabulary.exchange.VocabularySchema;
import io.goobi.workflow.api.vocabulary.VocabularyAPIManager;
import io.goobi.workflow.api.vocabulary.helper.ExtendedVocabulary;
import io.goobi.workflow.api.vocabulary.helper.ExtendedVocabularyRecord;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
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
import org.primefaces.event.CloseEvent;
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

import javax.faces.context.FacesContext;
import javax.faces.model.SelectItem;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

    private static final long serialVersionUID = -2900229011356745663L;

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
    private transient Fileformat fileformat;
    private DocStruct logical;
    private DocStruct anchor;
    private DocStruct physical;

    @Getter
    private transient List<Image> allImages = new ArrayList<>();

    @Getter
    private int thumbnailSize = 200;

    @Getter
    private boolean hideEmptyFields = true;

    @Getter
    private boolean onlyEmptyReadOnlyFields;

    private boolean pagesRTL;
    @Getter
    @Setter
    private transient Image image = null;

    private Integer imageIndex = null;

    @Getter
    private transient List<MetadataConfiguredField> metadataFieldList = new ArrayList<>();

    @Getter
    @Setter
    private transient MetadataField currentField;

    @Getter
    @Setter
    private String searchValue;
    @Getter
    @Setter
    private transient List<ProcessMetadata> processList;

    private transient Map<String, WhiteListItem> metadataWhiteListToImport = new LinkedHashMap<>();
    private boolean preselectFields;

    @Getter
    @Setter
    private transient ProcessMetadata selectedProcess;

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

    @Getter
    private boolean displayMetadataAddButton = true;

    @Getter
    private boolean displayDownloadButton = false;

    private transient List<MetadataField> deleteList = new ArrayList<>();

    @Getter
    @Setter
    private String newValue;

    private String newField;

    @Getter
    private transient MetadataConfiguredField selectedField;

    @Getter
    @Setter
    private boolean displayMetadataAddPopup = false;


    private VocabularyAPIManager vocabularyAPI = VocabularyAPIManager.getInstance();

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
        return this.returnPath;
    }

    @Override
    public String getPagePath() {
        return null;
    }

    @Override
    public void initialize(Step step, String returnPath) {
        this.step = step;
        this.returnPath = returnPath;

        this.process = step.getProzess();

        this.prefs = this.process.getRegelsatz().getPreferences();
        try {
            this.fileformat = this.process.readMetadataFile();
            DigitalDocument digitalDocument = fileformat.getDigitalDocument();
            this.logical = digitalDocument.getLogicalDocStruct();
            if (this.logical.getType().isAnchor()) {
                this.anchor = this.logical;
                this.logical = this.logical.getAllChildren().get(0);
            }
            this.physical = digitalDocument.getPhysicalDocStruct();
        } catch (ReadException | PreferencesException | IOException | SwapException e1) {
            log.error(e1);
        }

        if (this.physical != null && this.physical.getAllMetadata() != null) {
            List<Metadata> lstMetadata = this.physical.getAllMetadata();
            for (Metadata md : lstMetadata) {
                if ("_representative".equals(md.getType().getName())) {
                    try {
                        Integer value = Integer.parseInt(md.getValue());
                        if (value > 0) {
                            this.imageIndex = value - 1;
                        }
                    } catch (Exception e) {
                        log.error(e);
                    }
                }
            }
        }

        if (this.logical.getAllMetadata() != null) {
            for (Metadata md : this.logical.getAllMetadata()) {
                if ("_directionRTL".equals(md.getType().getName())) {
                    try {
                        this.pagesRTL = Boolean.parseBoolean(md.getValue());
                    } catch (Exception e) {
                        // do nothing
                    }
                }
            }
        }

        // read parameters from correct block in configuration file
        this.myconfig = ConfigPlugins.getProjectAndStepConfig(this.title, step);
        this.thumbnailSize = this.myconfig.getInt("thumbnailsize", 200);
        this.hideEmptyFields = this.myconfig.getBoolean("hideEmptyFields", true);
        this.onlyEmptyReadOnlyFields = this.myconfig.getBoolean("hideEmptyFields/@onlyEmptyReadOnlyFields", true);

        this.displayImageArea = this.myconfig.getBoolean("showImages", false);
        this.displayMetadataImportButton = this.myconfig.getBoolean("showImportMetadata", false);
        this.displayMetadataAddButton = this.myconfig.getBoolean("showAddMetadata", false);
        displayDownloadButton = myconfig.getBoolean("showDownloadFiles", false);

        try {
            if ("master".equalsIgnoreCase(this.myconfig.getString("imageFolder", null))) {
                this.imageFolderName = this.process.getImagesOrigDirectory(true);
            } else {
                this.imageFolderName = this.process.getImagesTifDirectory(true);
            }
        } catch (IOException | SwapException | DAOException e3) {
            log.error(e3);
        }
        initDisplayFields(this.myconfig);
        initSearchFields(this.myconfig);
        initImageList();
    }

    private void initSearchFields(SubnodeConfiguration config) {
        this.preselectFields = config.getBoolean("/preselectFields");
        List<HierarchicalConfiguration> fieldList = config.configurationsAt("/importfield");
        for (HierarchicalConfiguration field : fieldList) {
            String rulesetName = field.getString("@rulesetName");
            String label = field.getString("@label", rulesetName);
            boolean selectable = field.getBoolean("@selectable", false);
            WhiteListItem wli = new WhiteListItem(rulesetName, label, selectable);
            this.metadataWhiteListToImport.put(rulesetName, wli);
        }

    }

    private void initDisplayFields(SubnodeConfiguration config) {

        List<Processproperty> properties = this.process.getEigenschaften();

        this.metadataFieldList.clear();

        //* Allow multiple properties to be entered and selected as checkboxes, drop down lists and as input text fields
        // get <field> list
        List<HierarchicalConfiguration> fieldList = config.configurationsAt("/displayfield");
        for (HierarchicalConfiguration field : fieldList) {
            // each field has source, name, type, required attributes
            String source = field.getString("@source");
            String name = field.getString("@name");
            String fieldType = field.getString("@type");
            String label = field.getString("@label", name);
            boolean required = field.getBoolean("@required", false);
            String structType = field.getString("@structType", "child");
            String helpText = field.getString("@helpText", "No help text defined.");

            boolean searchable = field.getBoolean("@searchable", false);
            boolean repeatable = field.getBoolean("@repeatable", false);
            boolean deletable = field.getBoolean("@deletable", false);
            String searchSuffix = field.getString("@suffix");

            // each field can have defaultValue, validationRegex, validationErrorText, value (list) sub elements

            String defaultValue = field.getString("/defaultValue", null);
            String validationRegex = field.getString("/validationRegex", null);
            String validationErrorText = field.getString("/validationErrorText", "Value ist invalid");
            List<String> valueList = Arrays.asList(field.getStringArray("/value"));
            String vocabularyName = null;
            String vocabularyUrl = null;
            List<SelectItem> vocabularyRecords = null;
            if ("vocabularyList".equals(fieldType)) {
                vocabularyName = field.getString("/vocabularyName");
                List<String> fields = Arrays.asList(field.getStringArray("/searchParameter"));

                if (fields == null || fields.isEmpty()) {
                    ExtendedVocabulary vocabulary = vocabularyAPI.vocabularies().findByName(vocabularyName);
                    vocabularyUrl = vocabulary.getURI();

                    vocabularyRecords = vocabularyAPI.vocabularyRecords()
                            .list(vocabulary.getId())
                            .all()
                            .request()
                            .getContent()
                            .stream()
                            .map(r -> new SelectItem(String.valueOf(r.getId()), r.getMainValue()))
                            .collect(Collectors.toList());
                } else {
                    if (fields.size() > 1) {
                        Helper.setFehlerMeldung("vocabularyList with multiple fields is not supported right now");
                        return;
                    }

                    String[] parts = fields.get(0).trim().split("=");
                    if (parts.length != 2) {
                        Helper.setFehlerMeldung("Wrong field format");
                        return;
                    }

                    String searchFieldName = parts[0];
                    String searchFieldValue = parts[1];

                    ExtendedVocabulary vocabulary = vocabularyAPI.vocabularies().findByName(vocabularyName);
                    vocabularyUrl = vocabulary.getURI();
                    VocabularySchema schema = vocabularyAPI.vocabularySchemas().get(vocabulary.getSchemaId());
                    Optional<FieldDefinition> searchField = schema.getDefinitions().stream()
                            .filter(d -> d.getName().equals(searchFieldName))
                            .findFirst();

                    if (searchField.isEmpty()) {
                        Helper.setFehlerMeldung("Field " + searchFieldName + " not found in vocabulary " + vocabulary.getName());
                        return;
                    }

                    vocabularyRecords = vocabularyAPI.vocabularyRecords()
                            .list(vocabulary.getId())
                            .search(searchField.get().getId() + ":" + searchFieldValue)
                            .all()
                            .request()
                            .getContent()
                            .stream()
                            .map(r -> new SelectItem(String.valueOf(r.getId()), r.getMainValue()))
                            .collect(Collectors.toList());
                }
            }

            if (vocabularyRecords == null) {
                vocabularyRecords = Collections.emptyList();
            }

            MetadataConfiguredField metadataField = new MetadataConfiguredField(source, name, fieldType, label, required, helpText, searchable);
            metadataField.setStructType(structType);
            metadataField.setDefaultValue(defaultValue);
            metadataField.setValidationRegex(validationRegex);
            metadataField.setValidationErrorText(validationErrorText);
            metadataField.setValueList(valueList);
            metadataField.setSearchSuffix(searchSuffix);
            metadataField.setVocabularyList(vocabularyRecords);
            metadataField.setVocabularyName(vocabularyName);
            metadataField.setVocabularyUrl(vocabularyUrl);
            metadataField.setRepeatable(repeatable);
            metadataField.setDeletable(deletable);
            metadataField.setOnlyEmptyReadOnlyFields(this.onlyEmptyReadOnlyFields);
            this.metadataFieldList.add(metadataField);
        }

        for (MetadataConfiguredField cf : this.metadataFieldList) {
            boolean found = false;
            if ("property".contains(cf.getSource())) {
                for (Processproperty prop : properties) {
                    if (prop.getTitel().equals(cf.getName())) {
                        found = true;
                        MetadataField mf = new MetadataField(cf);
                        mf.setProperty(prop);
                        if (StringUtils.isBlank(prop.getWert())) {
                            prop.setWert(cf.getDefaultValue());
                        }
                        cf.addMetadataField(mf);
                    }
                }
                if (!found) {
                    Processproperty property = new Processproperty();
                    property.setContainer(0);
                    property.setCreationDate(new Date());
                    property.setProcessId(this.process.getId());
                    property.setProzess(this.process);
                    property.setTitel(cf.getName());
                    property.setType(PropertyType.STRING);
                    property.setWert(cf.getDefaultValue());
                    this.process.getEigenschaften().add(property);
                    MetadataField mf = new MetadataField(cf);
                    mf.setProperty(property);
                    property.setWert(cf.getDefaultValue());
                    cf.addMetadataField(mf);
                }

            } else if ("metadata".contains(cf.getSource())) {
                List<Metadata> metadataList;
                if (this.anchor != null && "anchor".equals(cf.getStructType())) {
                    metadataList = this.anchor.getAllMetadata();
                } else {
                    metadataList = this.logical.getAllMetadata();
                }

                if (metadataList != null) {
                    for (Metadata md : metadataList) {
                        if (md.getType().getName().equals(cf.getName())) {
                            found = true;
                            if (StringUtils.isBlank(md.getValue())) {
                                md.setValue(cf.getDefaultValue());
                            }
                            MetadataField mf = new MetadataField(cf);
                            mf.setMetadata(md);
                            cf.addMetadataField(mf);
                        }
                    }
                }
                if (!found) {
                    try {
                        Metadata md = new Metadata(this.prefs.getMetadataTypeByName(cf.getName()));
                        md.setValue(cf.getDefaultValue());
                        if (this.anchor != null && "anchor".equals(cf.getStructType())) {
                            this.anchor.addMetadata(md);
                        } else {
                            this.logical.addMetadata(md);
                        }
                        MetadataField mf = new MetadataField(cf);
                        mf.setMetadata(md);
                        cf.addMetadataField(mf);
                    } catch (MetadataTypeNotAllowedException e) {
                        log.error(e);
                    }
                }

            } else {
                // person
                List<Person> personList;
                if (this.anchor != null && "anchor".equals(cf.getStructType())) {
                    personList = this.anchor.getAllPersons();
                } else {
                    personList = this.logical.getAllPersons();
                }

                if (personList != null) {
                    for (Person p : personList) {
                        if (p.getType().getName().equals(cf.getName())) {
                            found = true;
                            MetadataField mf = new MetadataField(cf);
                            mf.setPerson(p);
                            cf.addMetadataField(mf);
                        }
                    }
                }
                if (!found) {
                    try {
                        Person person = new Person(this.prefs.getMetadataTypeByName(cf.getName()));
                        if (this.anchor != null && "anchor".equals(cf.getStructType())) {
                            this.anchor.addPerson(person);
                        } else {
                            this.logical.addPerson(person);
                        }
                        MetadataField mf = new MetadataField(cf);
                        mf.setPerson(person);
                        cf.addMetadataField(mf);
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
        return null; //NOSONAR
    }

    @Override
    public int getInterfaceVersion() {
        return 0;
    }

    private void initImageList() {
        Path path = Paths.get(this.imageFolderName);
        if (StorageProvider.getInstance().isFileExists(path)) {
            List<String> imageNameList = StorageProvider.getInstance().list(this.imageFolderName, NIOFileUtils.imageOrObjectNameFilter);
            int order = 1;
            for (String imagename : imageNameList) {

                try {
                    Image currentImage = new Image(this.process, this.imageFolderName, imagename, order, this.thumbnailSize);

                    this.allImages.add(currentImage);
                    order++;
                } catch (IOException | SwapException | DAOException e) {
                    log.error("Error initializing image " + imagename, e);
                }
            }
            if (!imageNameList.isEmpty() && imageIndex == null) {
                imageIndex = 0;
            }
        }
        setImageIndex(this.imageIndex);
    }

    public int getSizeOfImageList() {
        return this.allImages.size();
    }

    public String getFlowDir() {
        return this.pagesRTL ? "rtl" : "ltr";
    }

    public void setImageIndex(Integer imageIndex) {
        if (imageIndex != null) {
            this.imageIndex = imageIndex;
            if (this.imageIndex < 0) {
                this.imageIndex = 0;
            }
            if (this.imageIndex >= getSizeOfImageList()) {
                this.imageIndex = getSizeOfImageList() - 1;
            }
            if (this.imageIndex >= 0) {
                setImage(this.allImages.get(this.imageIndex));
            }
        }
    }

    // import metadata from other process
    public void importMetadataFromExternalProcess() {
        if (this.selectedProcess == null) {
            return;
        }
        // load metadata of the process
        Process other = ProcessManager.getProcessById(this.selectedProcess.getProcessId());
        DocStruct otherLogical = null;
        DocStruct otherAnchor = null;
        try {
            Fileformat otherFormat = other.readMetadataFile();

            otherLogical = otherFormat.getDigitalDocument().getLogicalDocStruct();
            if (otherLogical.getType().isAnchor()) {
                otherAnchor = otherLogical;
                otherLogical = otherLogical.getAllChildren().get(0);
            }

        } catch (ReadException | PreferencesException | IOException | SwapException e) {
            log.error(e);
        }

        // get list of metadata types
        List<String> metadataTypesToDelete = new ArrayList<>();
        for (ProcessMetadataField pmf : this.selectedProcess.getMetadataFieldList()) {
            if (pmf.isSelected() && !metadataTypesToDelete.contains(pmf.getMetadataName())) {
                metadataTypesToDelete.add(pmf.getMetadataName());
            }
        }
        // remove existing types from current element
        for (String metadataType : metadataTypesToDelete) {
            removeMetadata(metadataType, this.logical);
        }
        if (this.anchor != null) {
            for (String metadataType : metadataTypesToDelete) {
                removeMetadata(metadataType, this.anchor);
            }
        }

        // add new metadata
        for (ProcessMetadataField pmf : this.selectedProcess.getMetadataFieldList()) {
            if (pmf.isSelected()) {
                importSelectedMetadata(otherLogical, pmf, this.logical);
            }
            if (this.anchor != null && otherAnchor != null) {
                importSelectedMetadata(otherAnchor, pmf, this.anchor);
            }
        }

        // update metadataFieldList
        initDisplayFields(this.myconfig);
    }

    private void importSelectedMetadata(DocStruct source, ProcessMetadataField pmf, DocStruct destination) {
        MetadataType mdt = this.prefs.getMetadataTypeByName(pmf.getMetadataName());
        if (mdt.getIsPerson()) {
            List<Person> personList = source.getAllPersonsByType(mdt);
            Person personToImport = null;
            if (personList != null) {
                for (Person person : personList) {
                    if (StringUtils.isNotBlank(person.getFirstname()) && StringUtils.isNotBlank(person.getLastname())) {
                        if ((person.getFirstname() + " " + person.getLastname()).equals(pmf.getValue())) {
                            personToImport = person;
                            break;
                        }
                    } else if ((StringUtils.isNotBlank(person.getFirstname()) && pmf.getValue().equals(person.getFirstname()))
                            || pmf.getValue().equals(person.getLastname())) {
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
                    p.setAuthorityFile(personToImport.getAuthorityID(), personToImport.getAuthorityURI(), personToImport.getAuthorityValue());
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
                    md.setAuthorityFile(metadataToImport.getAuthorityID(), metadataToImport.getAuthorityURI(), metadataToImport.getAuthorityValue());
                    destination.addMetadata(md);
                } catch (MetadataTypeNotAllowedException e) {
                    log.error(e);
                }
            }
        }
        this.displaySearchPopup = false;
    }

    private void removeMetadata(String metadataType, DocStruct docstruct) {
        List<? extends Metadata> mdl = docstruct.getAllMetadataByType(this.prefs.getMetadataTypeByName(metadataType));
        if (mdl != null) {
            for (Metadata md : mdl) {
                docstruct.removeMetadata(md);
            }
        }
        List<Person> personList = docstruct.getAllPersonsByType(this.prefs.getMetadataTypeByName(metadataType));
        if (personList != null) {
            for (Person p : personList) {
                docstruct.removePerson(p);
            }
        }
    }

    public void saveAllChanges() {

        boolean totalValid = true;
        for (MetadataConfiguredField mf : this.metadataFieldList) {
            if (mf.getRequired().booleanValue()) {
                boolean fieldValid = false;
                for (MetadataField metadataField : mf.getMetadataFields()) {
                    if (StringUtils.isNotBlank(metadataField.getValue())) {
                        fieldValid = true;
                    }
                }
                if (!fieldValid) {
                    Helper.setFehlerMeldung(mf.getLabel() + ": " + Helper.getTranslation("valueIsRequired"), "");
                    totalValid = false;
                }

            } else if (StringUtils.isNotBlank(mf.getValidationRegex())) {
                for (MetadataField metadataField : mf.getMetadataFields()) {
                    if (!metadataField.getValue().matches(mf.getValidationRegex())) {
                        Helper.setFehlerMeldung(mf.getLabel() + ": " + mf.getValidationErrorText(), "");
                        totalValid = false;
                    }
                }
            }
        }

        if (!totalValid) {
            return;
        }

        for (MetadataField mf : this.deleteList) {
            if ("property".contains(mf.getConfiguredField().getSource())) {
                Processproperty pp = mf.getProperty();
                this.process.getEigenschaften().remove(pp);
                if (pp.getId() != null) {
                    PropertyManager.deleteProcessProperty(pp);
                }
            } else if ("metadata".contains(mf.getConfiguredField().getSource())) {
                Metadata md = mf.getMetadata();
                if (md.getParent() != null) {
                    md.getParent().removeMetadata(md, true);
                }
            } else {
                Person p = mf.getPerson();
                if (p.getParent() != null) {
                    p.getParent().removePerson(p, true);
                }
            }
        }

        // save properties
        for (MetadataConfiguredField cf : this.metadataFieldList) {
            for (MetadataField mf : cf.getMetadataFields()) {
                if (mf.getProperty() != null) {
                    PropertyManager.saveProcessProperty(mf.getProperty());
                }
            }
        }
        // save reading direction
        if (this.logical.getAllMetadata() != null) {
            boolean match = false;
            for (Metadata md : this.logical.getAllMetadata()) {
                if ("_directionRTL".equals(md.getType().getName())) {
                    md.setValue(String.valueOf(this.pagesRTL));
                    match = true;
                }
            }
            if (!match) {
                MetadataType mdt = this.prefs.getMetadataTypeByName("_directionRTL");
                if (mdt != null) {
                    try {
                        Metadata md = new Metadata(mdt);
                        md.setValue(String.valueOf(this.pagesRTL));
                        this.logical.addMetadata(md);
                    } catch (MetadataTypeNotAllowedException e) {
                        // do nothing
                    }
                }
            }
        }
        // save representative image
        if (imageIndex != null) {
            boolean match = false;
            if (this.physical != null && this.physical.getAllMetadata() != null && !this.physical.getAllMetadata().isEmpty()) {
                for (Metadata md : this.physical.getAllMetadata()) {
                    if ("_representative".equals(md.getType().getName())) {
                        md.setValue(String.valueOf(this.imageIndex + 1));
                        match = true;
                    }
                }
            }
            if (!match) {
                MetadataType mdt = this.prefs.getMetadataTypeByName("_representative");
                try {
                    Metadata md = new Metadata(mdt);
                    Integer value = this.imageIndex + 1;
                    md.setValue(String.valueOf(value));

                    this.physical.addMetadata(md);
                } catch (MetadataTypeNotAllowedException e) {
                    // do nothing
                }
            }
        }

        // save mets file

        try {
            this.process.writeMetadataFile(this.fileformat);
        } catch (WriteException | PreferencesException | IOException | SwapException e) {
            log.error(e);
        }
    }

    public void searchForMetadata() {
        String sql = FilterHelper.criteriaBuilder(this.searchValue + " -id:" + this.process.getId(), false, null, null, null, true, false);
        sql = sql + " and prozesse.istTemplate = false ";
        Map<Integer, String> foundProcessIds = getAllProcessesWithMetadata(sql);

        this.processList = new ArrayList<>(foundProcessIds.size());

        for (Entry<Integer, String> entry : foundProcessIds.entrySet()) {
            List<StringPair> metadataList = MetadataManager.getMetadata(entry.getKey());
            ProcessMetadata pm =
                    new ProcessMetadata(entry.getKey(), entry.getValue(), metadataList, this.metadataWhiteListToImport, this.preselectFields);
            this.processList.add(pm);
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

    public static final ResultSetHandler<Map<Integer, String>> resultSetToMapHandler = new ResultSetHandler<>() {
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
                rs.close();
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
        return this.processList == null || this.processList.isEmpty();
    }

    public void seachField() {

        StringBuilder searchQuery = new StringBuilder();

        switch (this.currentField.getConfiguredField().getSource()) {
            case "property":
                // "processproperty:name:value"
                searchQuery.append("\"processproperty:");
                searchQuery.append(this.currentField.getConfiguredField().getName());
                searchQuery.append(":");
                searchQuery.append(this.currentField.getValue());
                searchQuery.append("\" ");
                break;
            case "metadata":
                // "meta:name:value"
                searchQuery.append("\"meta:");
                searchQuery.append(this.currentField.getConfiguredField().getName());
                searchQuery.append(":");
                searchQuery.append(this.currentField.getValue());
                searchQuery.append("\" ");
                break;
            case "person":
                // "meta:name:firstname lastname"
                String personName = null;
                if (StringUtils.isNotBlank(this.currentField.getPerson().getFirstname())
                        && StringUtils.isNotBlank(this.currentField.getPerson().getLastname())) {
                    personName = this.currentField.getPerson().getFirstname() + " " + this.currentField.getPerson().getLastname();
                } else if (StringUtils.isNotBlank(this.currentField.getPerson().getFirstname())) {
                    personName = this.currentField.getPerson().getFirstname();
                } else {
                    personName = this.currentField.getPerson().getLastname();
                }
                searchQuery.append("\"meta:");
                searchQuery.append(this.currentField.getConfiguredField().getName());
                searchQuery.append(":");
                searchQuery.append(personName);
                searchQuery.append("\" ");

                break;
            default:

        }
        // addd configured suffix
        String suffix = this.currentField.getConfiguredField().getSearchSuffix();
        if (StringUtils.isNotBlank(suffix)) {
            searchQuery.append(suffix);
        }
        // exclude current process id
        searchQuery.append(" -id:").append(this.process.getId());

        String sql = FilterHelper.criteriaBuilder(searchQuery.toString(), false, null, null, null, true, false);
        sql = sql + " and prozesse.istTemplate = false ";
        Map<Integer, String> foundProcessIds = getAllProcessesWithMetadata(sql);

        this.processList = new ArrayList<>(foundProcessIds.size());

        for (Entry<Integer, String> entry : foundProcessIds.entrySet()) {
            List<StringPair> metadataList = MetadataManager.getMetadata(entry.getKey());
            ProcessMetadata pm =
                    new ProcessMetadata(entry.getKey(), entry.getValue(), metadataList, this.metadataWhiteListToImport, this.preselectFields);
            this.processList.add(pm);
        }
        this.displaySearchOption = false;
        this.displaySearchPopup = true;
    }

    public void handleClose(CloseEvent event) { //NOSONAR
        this.displaySearchPopup = false;
        this.displayMetadataAddPopup = false;
    }

    public void openPopup() {
        this.processList = null;
        this.displaySearchOption = true;
        this.displaySearchPopup = true;
        this.displayMetadataAddPopup = false;
    }

    public void openMetadataPopup() {
        this.newField = null;
        this.newValue = null;

        this.displaySearchPopup = false;
        this.displayMetadataAddPopup = true;
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

    // unused
    public void addField() {
        MetadataConfiguredField field = this.currentField.getConfiguredField();
        if (field != null) {
            MetadataField metadataField = new MetadataField(this.currentField.getConfiguredField());
            field.addMetadataField(metadataField);

            if ("property".contains(field.getSource())) {
                Processproperty property = new Processproperty();
                property.setContainer(0);
                property.setCreationDate(new Date());
                property.setProcessId(this.process.getId());
                property.setProzess(this.process);
                property.setTitel(field.getName());
                property.setType(PropertyType.STRING);
                property.setWert(this.currentField.getProperty().getWert());
                this.process.getEigenschaften().add(property);
                metadataField.setProperty(property);

            } else if ("metadata".contains(field.getSource())) {
                try {
                    Metadata md = new Metadata(this.currentField.getMetadata().getType());
                    md.setValue(this.currentField.getMetadata().getValue());
                    this.currentField.getMetadata().getParent().addMetadata(md);
                    metadataField.setMetadata(md);
                } catch (MetadataTypeNotAllowedException e) {
                    log.error(e);
                }

            } else {
                try {
                    Person person = new Person(this.currentField.getPerson().getType());
                    person.setFirstname(this.currentField.getPerson().getFirstname());
                    person.setLastname(this.currentField.getPerson().getLastname());
                    person.setAuthorityFile(this.currentField.getPerson().getAuthorityID(), this.currentField.getPerson().getAuthorityURI(),
                            this.currentField.getPerson().getAuthorityValue());
                    this.currentField.getPerson().getParent().addPerson(person);
                    metadataField.setPerson(person);

                } catch (MetadataTypeNotAllowedException e) {
                    log.error(e);
                }
            }
        }
    }

    public void deleteField() {
        if (this.currentField != null) {
            for (MetadataConfiguredField cf : this.metadataFieldList) {
                if (cf.getSource().equals(this.currentField.getConfiguredField().getSource())) {
                    cf.getMetadataFields().remove(this.currentField);
                }
            }
            this.deleteList.add(this.currentField);
            this.currentField = null;
        }
    }

    public List<SelectItem> getPossibleFields() {
        List<SelectItem> answer = new ArrayList<>();
        for (MetadataConfiguredField cf : this.metadataFieldList) {
            if ((cf.isRepeatable() || cf.getMetadataFields().isEmpty())
                    && (!"textReadonly".equals(cf.getType()) && !"textareaReadonly".equals(cf.getType()))) {
                answer.add(new SelectItem(cf.getName(), cf.getLabel()));
            }
        }
        return answer;
    }

    public void addMetadataField() {
        this.displayMetadataAddPopup = false;

        MetadataField mf = new MetadataField(this.selectedField);
        this.selectedField.addMetadataField(mf);
        switch (this.selectedField.getSource()) {
            case "property":
                Processproperty property = new Processproperty();
                property.setContainer(0);
                property.setCreationDate(new Date());
                property.setProcessId(this.process.getId());
                property.setProzess(this.process);
                property.setTitel(this.selectedField.getName());
                property.setType(PropertyType.STRING);
                property.setWert(this.selectedField.getDefaultValue());
                this.process.getEigenschaften().add(property);
                mf.setProperty(property);
                if ("vocabularyList".equals(this.selectedField.getType())) {
                    for (SelectItem item : this.selectedField.getVocabularyList()) {
                        if (this.newValue.equals(item.getValue())) {
                            property.setWert(item.getLabel());
                            break;
                        }
                    }
                } else {
                    property.setWert(this.newValue);
                }
                break;
            case "metadata":
                try {
                    Metadata md = new Metadata(this.prefs.getMetadataTypeByName(this.selectedField.getName()));
                    if ("vocabularyList".equals(this.selectedField.getType())) {
                        for (SelectItem item : this.selectedField.getVocabularyList()) {
                            if (this.newValue.equals(item.getValue())) {
                                md.setValue(item.getLabel());
                                md.setAuthorityFile(this.selectedField.getVocabularyName(), this.selectedField.getVocabularyUrl(),
                                        this.selectedField.getVocabularyUrl() + "/" + this.newValue);
                                break;
                            }
                        }
                    } else {
                        md.setValue(this.newValue);
                    }
                    if (this.anchor != null && "anchor".equals(this.selectedField.getStructType())) {
                        this.anchor.addMetadata(md);
                    } else {
                        this.logical.addMetadata(md);
                    }
                    mf.setMetadata(md);
                } catch (MetadataTypeNotAllowedException e) {
                    log.error(e);
                }
                break;
            default:
                try {
                    Person person = new Person(this.prefs.getMetadataTypeByName(this.selectedField.getName()));
                    if (this.anchor != null && "anchor".equals(this.selectedField.getStructType())) {
                        this.anchor.addPerson(person);
                    } else {
                        this.logical.addPerson(person);
                    }
                    person.setLastname(this.newValue);
                    mf.setPerson(person);
                } catch (MetadataTypeNotAllowedException e) {
                    log.error(e);
                }
                break;
        }

    }

    public String getNewField() {
        return this.newField;
    }

    public void setNewField(String newField) {
        if (this.newField == null || !this.newField.equals(newField)) {
            for (MetadataConfiguredField cf : this.metadataFieldList) {
                if (cf.getName().equals(newField)) {
                    this.selectedField = cf;
                    break;
                }
            }
            this.newField = newField;
        }
    }

    public void downloadAllFiles() throws IOException {
        // get all files in folder
        Path path = Paths.get(imageFolderName);
        if (StorageProvider.getInstance().isFileExists(path)) {
            List<String> files = StorageProvider.getInstance().list(imageFolderName);

            // if no files -> do nothing
            if (files.isEmpty()) {
                return;
            } else {

                FacesContext facesContext = FacesContextHelper.getCurrentFacesContext();
                HttpServletResponse response = (HttpServletResponse) facesContext.getExternalContext().getResponse();
                ServletContext servletContext = (ServletContext) facesContext.getExternalContext().getContext();
                OutputStream out = response.getOutputStream();

                if (files.size() == 1) {
                    // if one file -> write to output stream
                    String fileName = files.get(0);
                    String contentType = servletContext.getMimeType(fileName);
                    response.setContentType(contentType);
                    response.setHeader("Content-Disposition", "attachment;filename=\"" + fileName + "\"");

                    Path p = Paths.get(imageFolderName, fileName);
                    try (InputStream inputStream = StorageProvider.getInstance().newInputStream(p)) {
                        inputStream.transferTo(out);
                    }

                } else {
                    // if more than one -> create zip, send zip to stream

                    response.setContentType("application/zip");
                    response.setHeader("Content-Disposition", "attachment;filename=\"" + process.getTitel() + ".zip\"");

                    ZipOutputStream zos = new ZipOutputStream(out);
                    for (String fileName : files) {

                        try (InputStream in = StorageProvider.getInstance().newInputStream(Paths.get(imageFolderName, fileName))) {
                            zos.putNextEntry(new ZipEntry(fileName));
                            byte[] b = new byte[1024];
                            int count;

                            while ((count = in.read(b)) > 0) {
                                out.write(b, 0, count);
                            }
                        }
                    }
                    zos.flush();
                    zos.close();

                }

            }
        }

    }

}
