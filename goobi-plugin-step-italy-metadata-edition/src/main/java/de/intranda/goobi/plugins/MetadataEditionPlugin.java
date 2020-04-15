package de.intranda.goobi.plugins;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.lang.StringUtils;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.sub.goobi.helper.NIOFileUtils;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.metadaten.Image;
import de.sub.goobi.persistence.managers.MetadataManager;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
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

    @Getter
    private List<Image> allImages = new ArrayList<>();

    // TODO configurable?
    @Getter
    private int thumbnailSize = 200;

    private boolean pagesRTL;
    @Getter @Setter
    private Image image = null;

    private int imageIndex;

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
        if (run().equals(PluginReturnValue.FINISH)) {
            return true;
        } else {
            return false;
        }
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

        //        String projectName = step.getProzess().getProjekt().getTitel();
        //
        //        XMLConfiguration xmlConfig = ConfigPlugins.getPluginConfig(getTitle());
        //        xmlConfig.setExpressionEngine(new XPathExpressionEngine());
        //        xmlConfig.setReloadingStrategy(new FileChangedReloadingStrategy());
        //
        //        SubnodeConfiguration myconfig = null;
        //
        //        // order of configuration is:
        //        // 1.) project name and step name matches
        //        // 2.) step name matches and project is *
        //        // 3.) project name matches and step name is *
        //        // 4.) project name and step name are *
        //        try {
        //            myconfig = xmlConfig.configurationAt("//config[./project = '" + projectName + "'][./step = '" + step.getTitel() + "']");
        //        } catch (IllegalArgumentException e) {
        //            try {
        //                myconfig = xmlConfig.configurationAt("//config[./project = '*'][./step = '" + step.getTitel() + "']");
        //            } catch (IllegalArgumentException e1) {
        //                try {
        //                    myconfig = xmlConfig.configurationAt("//config[./project = '" + projectName + "'][./step = '*']");
        //                } catch (IllegalArgumentException e2) {
        //                    myconfig = xmlConfig.configurationAt("//config[./project = '*'][./step = '*']");
        //                }
        //            }
        //        }
        //
        //        initConfig(config);
        //check pages RTL:
        try {
            this.pagesRTL = readPagesRTLFromXML();

        } catch (Exception e) {
            //by default, false
        }
        initImageList();
    }

    private void initConfig(SubnodeConfiguration config) {


    }

    private boolean readPagesRTLFromXML()
            throws ReadException, PreferencesException, WriteException, IOException, InterruptedException, SwapException, DAOException {

        String readingDirection = MetadataManager.getMetadataValue(process.getId(), "_directionRTL");
        if (StringUtils.isNotBlank(readingDirection)) {
            return Boolean.valueOf(readingDirection);
        }

        //        Fileformat gdzfile = process.readMetadataFile();
        //        if (gdzfile == null) {
        //            return false;
        //        }
        //
        //        DigitalDocument mydocument = gdzfile.getDigitalDocument();
        //
        //        DocStruct logicalTopstruct = mydocument.getLogicalDocStruct();
        //        if (logicalTopstruct.getType().isAnchor()) {
        //            logicalTopstruct = logicalTopstruct.getAllChildren().get(0);
        //        }
        //
        //        if (logicalTopstruct.getAllMetadata() != null) {
        //
        //            List<Metadata> lstMetadata = logicalTopstruct.getAllMetadata();
        //            for (Metadata md : lstMetadata) {
        //                if (md.getType().getName().equals("_directionRTL")) {
        //                    try {
        //                        boolean value = Boolean.valueOf(md.getValue());
        //                        return value;
        //                    } catch (Exception e) {
        //
        //                    }
        //                }
        //            }
        //        }

        //default:
        return false;
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
        setImageIndex(0);
        //check pages RTL:
        //        try {
        //            this.pagesRTL = readPagesRTLFromXML();
        //
        //        } catch (Exception e) {
        //            //by default, false
        //        }

    }


    public int getSizeOfImageList() {
        return allImages.size();
    }

    public String getFlowDir() {
        return this.pagesRTL ? "rtl" : "ltr";
    }




    public void setImageIndex(int imageIndex) {
        System.out.println(imageIndex);
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
}
