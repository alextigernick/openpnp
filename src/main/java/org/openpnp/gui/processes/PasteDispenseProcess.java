package org.openpnp.gui.processes;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

import org.openpnp.gui.JobPanel;
import org.openpnp.gui.MainFrame;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.BoardPad;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Pad;
import org.openpnp.model.PanelLocation;
import org.openpnp.model.Placement;
import org.openpnp.model.PlacementsHolderLocation;
import org.openpnp.spi.Actuator;
import org.openpnp.spi.Actuator.ActuatorValueType;
import org.openpnp.spi.FiducialLocator;
import org.openpnp.spi.Machine;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.TravellingSalesman;
import org.openpnp.util.UiUtils;
import org.pmw.tinylog.Logger;

public class PasteDispenseProcess {
    private static final String PROPERTY_NAME = "PasteDispenseProcessProperties";

    private final MainFrame mainFrame;
    private final JobPanel jobPanel;
    private final Machine machine;
    private final PasteDispenseProcessProperties properties;

    public static class PasteDispenseProcessProperties {
        public String actuatorId = "";
        public boolean runFiducialCheck = true;
        public double dispenseZOffsetMm = 0.0;
        public int postDispenseDwellMs = 0;
        public double smallMaxAreaMm2 = 0.5;
        public double mediumMaxAreaMm2 = 1.5;
        public double largeMaxAreaMm2 = 4.0;
        public String smallProfile = "";
        public String mediumProfile = "";
        public String largeProfile = "";
        public String extraLargeProfile = "";
    }

    private static class PastePadTarget {
        private final BoardLocation boardLocation;
        private final BoardPad pad;
        private final Location location;
        private final double areaMm2;
        private final String profile;

        private PastePadTarget(BoardLocation boardLocation, BoardPad pad, Location location,
                double areaMm2, String profile) {
            this.boardLocation = boardLocation;
            this.pad = pad;
            this.location = location;
            this.areaMm2 = areaMm2;
            this.profile = profile;
        }

        private String getDisplayName() {
            if (pad.getName() != null && !pad.getName().isEmpty()) {
                return pad.getName();
            }
            return boardLocation.getUniqueId();
        }
    }

    public PasteDispenseProcess(MainFrame mainFrame, JobPanel jobPanel) throws Exception {
        this.mainFrame = mainFrame;
        this.jobPanel = jobPanel;
        this.machine = Configuration.get().getMachine();
        PasteDispenseProcessProperties savedProperties =
                (PasteDispenseProcessProperties) machine.getProperty(PROPERTY_NAME);
        if (savedProperties == null) {
            savedProperties = new PasteDispenseProcessProperties();
            machine.setProperty(PROPERTY_NAME, savedProperties);
        }
        this.properties = savedProperties;

        if (!showConfigurationDialog()) {
            return;
        }

        UiUtils.submitUiMachineTask(this::run);
    }

    private boolean showConfigurationDialog() throws Exception {
        List<Actuator> actuators = new ArrayList<>(machine.getAllActuators());
        actuators.sort(Comparator.comparing(
                actuator -> actuator.getName() == null ? "" : actuator.getName().toLowerCase(Locale.US)));
        if (actuators.isEmpty()) {
            throw new Exception("No actuators are configured.");
        }

        JComboBox<Actuator> actuatorBox = new JComboBox<>(actuators.toArray(new Actuator[0]));
        actuatorBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list,
                    Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Actuator) {
                    Actuator actuator = (Actuator) value;
                    String scope = actuator.getHead() == null ? "Machine" : actuator.getHead().getName();
                    setText(actuator.getName() + " (" + scope + ")");
                }
                return this;
            }
        });

        Actuator selectedActuator = findActuatorById(properties.actuatorId);
        if (selectedActuator != null) {
            actuatorBox.setSelectedItem(selectedActuator);
        }

        JCheckBox fiducialCheckBox = new JCheckBox();
        fiducialCheckBox.setSelected(properties.runFiducialCheck);

        JTextField zOffsetField = new JTextField(
                String.format(Locale.US, "%.3f", properties.dispenseZOffsetMm), 10);
        JTextField dwellField =
                new JTextField(Integer.toString(properties.postDispenseDwellMs), 10);
        JTextField smallAreaField = new JTextField(
                String.format(Locale.US, "%.3f", properties.smallMaxAreaMm2), 10);
        JTextField mediumAreaField = new JTextField(
                String.format(Locale.US, "%.3f", properties.mediumMaxAreaMm2), 10);
        JTextField largeAreaField = new JTextField(
                String.format(Locale.US, "%.3f", properties.largeMaxAreaMm2), 10);

        JComboBox<String> smallProfileBox = createEditableProfileBox();
        JComboBox<String> mediumProfileBox = createEditableProfileBox();
        JComboBox<String> largeProfileBox = createEditableProfileBox();
        JComboBox<String> extraLargeProfileBox = createEditableProfileBox();

        Runnable refreshProfiles = () -> {
            Actuator actuator = (Actuator) actuatorBox.getSelectedItem();
            updateProfileChoices(smallProfileBox, actuator, properties.smallProfile);
            updateProfileChoices(mediumProfileBox, actuator, properties.mediumProfile);
            updateProfileChoices(largeProfileBox, actuator, properties.largeProfile);
            updateProfileChoices(extraLargeProfileBox, actuator, properties.extraLargeProfile);
        };
        actuatorBox.addActionListener(event -> refreshProfiles.run());
        refreshProfiles.run();

        JPanel panel = new JPanel(new GridBagLayout());
        int row = 0;
        addField(panel, row++, "Actuator", actuatorBox);
        addField(panel, row++, "Run fiducial check first", fiducialCheckBox);
        addField(panel, row++, "Dispense Z offset (mm)", zOffsetField);
        addField(panel, row++, "Post-dispense dwell (ms)", dwellField);
        addField(panel, row++, "Small max area (mm^2)", smallAreaField);
        addField(panel, row++, "Small profile", smallProfileBox);
        addField(panel, row++, "Medium max area (mm^2)", mediumAreaField);
        addField(panel, row++, "Medium profile", mediumProfileBox);
        addField(panel, row++, "Large max area (mm^2)", largeAreaField);
        addField(panel, row++, "Large profile", largeProfileBox);
        addField(panel, row++, "Extra-large profile", extraLargeProfileBox);

        int result = JOptionPane.showConfirmDialog(mainFrame, panel, "Paste Dispense",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return false;
        }

        Actuator chosenActuator = (Actuator) actuatorBox.getSelectedItem();
        if (chosenActuator == null) {
            throw new Exception("An actuator must be selected.");
        }

        properties.actuatorId = chosenActuator.getId();
        properties.runFiducialCheck = fiducialCheckBox.isSelected();
        properties.dispenseZOffsetMm = parseDouble(zOffsetField.getText(), "dispense Z offset");
        properties.postDispenseDwellMs = parseInt(dwellField.getText(), "post-dispense dwell");
        if (properties.postDispenseDwellMs < 0) {
            throw new Exception("Post-dispense dwell must be zero or greater.");
        }
        properties.smallMaxAreaMm2 = parseDouble(smallAreaField.getText(), "small max area");
        properties.mediumMaxAreaMm2 = parseDouble(mediumAreaField.getText(), "medium max area");
        properties.largeMaxAreaMm2 = parseDouble(largeAreaField.getText(), "large max area");
        if (properties.smallMaxAreaMm2 <= 0 || properties.mediumMaxAreaMm2 <= 0
                || properties.largeMaxAreaMm2 <= 0) {
            throw new Exception("Area thresholds must be greater than zero.");
        }
        if (!(properties.smallMaxAreaMm2 <= properties.mediumMaxAreaMm2
                && properties.mediumMaxAreaMm2 <= properties.largeMaxAreaMm2)) {
            throw new Exception("Area thresholds must be in ascending order.");
        }
        properties.smallProfile = getComboValue(smallProfileBox);
        properties.mediumProfile = getComboValue(mediumProfileBox);
        properties.largeProfile = getComboValue(largeProfileBox);
        properties.extraLargeProfile = getComboValue(extraLargeProfileBox);
        machine.setProperty(PROPERTY_NAME, properties);
        return true;
    }

    private void run() throws Exception {
        Actuator actuator = requireDispenseActuator();
        List<PlacementsHolderLocation<?>> selectedLocations =
                pruneNestedSelections(jobPanel.getSelections());
        if (selectedLocations.isEmpty()) {
            throw new Exception("Select at least one board or panel in the Job tab.");
        }

        int dispensedPads = 0;
        boolean foundAnyPads = false;
        for (PlacementsHolderLocation<?> selectedLocation : selectedLocations) {
            if (!selectedLocation.isEnabled()) {
                continue;
            }

            if (properties.runFiducialCheck) {
                locateFiducials(selectedLocation);
            }

            List<PastePadTarget> targets = collectTargets(selectedLocation);
            Logger.info("got targets: " + String.format("%d",targets.size()));
            if (targets.isEmpty()) {
                continue;
            }
            foundAnyPads = true;

            optimizeTravel(actuator, targets);
            for (PastePadTarget target : targets) {
                dispensedPads++;
                Logger.info("Dispensings for "+String.format("%d",dispensedPads));
                mainFrame.setStatus(String.format(Locale.US,
                        "Dispensing paste %d: %s on %s with %s (%.3f mm^2)", dispensedPads,
                        target.getDisplayName(), target.boardLocation.getUniqueId(), target.profile,
                        target.areaMm2));
                MovableUtils.moveToLocationAtSafeZ(actuator, target.location);
                actuator.actuateProfile(target.profile);
                if (properties.postDispenseDwellMs > 0) {
                    actuator.delay(properties.postDispenseDwellMs, actuator);
                }
            }
        }

        actuator.getHead().moveToSafeZ();
        jobPanel.refresh();

        if (!foundAnyPads) {
            throw new Exception("No solder paste pads were found on the selected board or panel.");
        }

        mainFrame.setStatus(String.format(Locale.US,
                "Paste dispensing complete. Dispensed %d pad(s).", dispensedPads));
    }

    private Actuator requireDispenseActuator() throws Exception {
        Actuator actuator = findActuatorById(properties.actuatorId);
        if (actuator == null) {
            throw new Exception("The configured paste actuator could not be found.");
        }
        if (actuator.getHead() == null) {
            throw new Exception("The selected paste actuator must be mounted on a head.");
        }
        if (actuator.getValueType() != ActuatorValueType.Profile) {
            throw new Exception(
                    "The selected paste actuator must use Profile values so pad sizes can map to G-code profiles.");
        }
        return actuator;
    }

    private void locateFiducials(PlacementsHolderLocation<?> selectedLocation) throws Exception {
        FiducialLocator locator = machine.getFiducialLocator();
        List<PlacementsHolderLocation<?>> locationsToLocate = new ArrayList<>();
        collectLocationsForFiducials(selectedLocation, locationsToLocate);
        if (locationsToLocate.isEmpty()) {
            locationsToLocate.add(selectedLocation);
        }
        for (PlacementsHolderLocation<?> location : locationsToLocate) {
            mainFrame.setStatus("Locating fiducials for " + location.getUniqueId());
            locator.locatePlacementsHolder(location);
        }
    }

    private void collectLocationsForFiducials(PlacementsHolderLocation<?> location,
            List<PlacementsHolderLocation<?>> locationsToLocate) {
        if (!location.isEnabled()) {
            return;
        }
        if (location.isCheckFiducials()) {
            locationsToLocate.add(location);
        }
        if (location instanceof PanelLocation) {
            for (PlacementsHolderLocation<?> child : ((PanelLocation) location).getPanel()
                    .getChildren()) {
                collectLocationsForFiducials(child, locationsToLocate);
            }
        }
    }

    private List<PastePadTarget> collectTargets(PlacementsHolderLocation<?> location)
            throws Exception {
        List<PastePadTarget> targets = new ArrayList<>();
        collectTargets(location, targets);
        return targets;
    }

    private void collectTargets(PlacementsHolderLocation<?> location, List<PastePadTarget> targets)
            throws Exception {
        if (!location.isEnabled()) {
            Logger.info("LOCATION NOT ENABLED");
            return;
        }
        
        if (location instanceof BoardLocation) {
            Logger.info("BOARDLOCATION");
            BoardLocation boardLocation = (BoardLocation) location;
            Logger.info("NUM pads "+String.format("%d", boardLocation.getBoard().getSolderPastePads().size()));
            for (BoardPad pad : boardLocation.getBoard().getSolderPastePads()) {
                if (pad == null || pad.getPad() == null || pad.getType() != BoardPad.Type.Paste) {
                    Logger.info("pad null or not paste "+ String.format("%b", pad == null || pad.getPad() == null));
                    continue;
                }
                if (pad.getSide() != boardLocation.getGlobalSide()) {
                    Logger.info("pad on wrong side");
                    continue;
                }
                double areaMm2 = calculatePadAreaMm2(pad);
                String profile = chooseProfile(areaMm2);
                Location padLocation = calculatePadLocation(boardLocation, pad);
                Logger.info("PAD at " + String.format("%f %f",padLocation.getX(),padLocation.getY()));

                targets.add(new PastePadTarget(boardLocation, pad, padLocation, areaMm2, profile));
            }
        }
        else if (location instanceof PanelLocation) {
            Logger.info("PANELLOCATION");

            for (PlacementsHolderLocation<?> child : ((PanelLocation) location).getPanel()
                    .getChildren()) {
                collectTargets(child, targets);
            }
        }
    }

    private Location calculatePadLocation(BoardLocation boardLocation, BoardPad pad) {
        Placement placement = new Placement(
                pad.getName() == null || pad.getName().isEmpty() ? "Paste Pad" : pad.getName());
        placement.removePropertyChangeListener(placement);
        placement.setLocation(pad.getLocation());
        Location location = org.openpnp.util.Utils2D.calculateBoardPlacementLocation(boardLocation,
                placement);
        double zOffset = Length.convertToUnits(properties.dispenseZOffsetMm, LengthUnit.Millimeters,
                location.getUnits());
        return location.add(new Location(location.getUnits(), 0, 0, zOffset, 0));
    }

    private double calculatePadAreaMm2(BoardPad pad) {
        Pad mmPad = pad.getPad().convertToUnits(LengthUnit.Millimeters);
        if (mmPad instanceof Pad.Circle) {
            double radius = ((Pad.Circle) mmPad).getRadius();
            return Math.PI * radius * radius;
        }
        if (mmPad instanceof Pad.Ellipse) {
            Pad.Ellipse ellipse = (Pad.Ellipse) mmPad;
            return Math.PI * ellipse.getWidth() * ellipse.getHeight() / 4.0;
        }
        if (mmPad instanceof Pad.RoundRectangle) {
            Pad.RoundRectangle rect = (Pad.RoundRectangle) mmPad;
            return rect.getWidth() * rect.getHeight();
        }
        Rectangle2D bounds = mmPad.getShape().getBounds2D();
        return bounds.getWidth() * bounds.getHeight();
    }

    private String chooseProfile(double areaMm2) throws Exception {
        String profile;
        if (areaMm2 <= properties.smallMaxAreaMm2) {
            profile = firstNonBlank(properties.smallProfile, properties.mediumProfile,
                    properties.largeProfile, properties.extraLargeProfile);
        }
        else if (areaMm2 <= properties.mediumMaxAreaMm2) {
            profile = firstNonBlank(properties.mediumProfile, properties.largeProfile,
                    properties.extraLargeProfile);
        }
        else if (areaMm2 <= properties.largeMaxAreaMm2) {
            profile = firstNonBlank(properties.largeProfile, properties.extraLargeProfile);
        }
        else {
            profile = firstNonBlank(properties.extraLargeProfile, properties.largeProfile);
        }

        if (profile == null || profile.isEmpty()) {
            throw new Exception("Paste profile mapping is incomplete. Configure at least one profile.");
        }
        return profile;
    }

    private void optimizeTravel(Actuator actuator, List<PastePadTarget> targets) {
        if (targets.size() < 2) {
            return;
        }
        TravellingSalesman<PastePadTarget> travellingSalesman = new TravellingSalesman<>(targets,
                target -> target.location, actuator.getLocation(), null, actuator);
        travellingSalesman.solve();
        List<PastePadTarget> orderedTargets = travellingSalesman.getTravel();
        targets.clear();
        targets.addAll(orderedTargets);
    }

    private List<PlacementsHolderLocation<?>> pruneNestedSelections(
            List<PlacementsHolderLocation<?>> selections) {
        Set<PlacementsHolderLocation<?>> selectionSet = new LinkedHashSet<>(selections);
        List<PlacementsHolderLocation<?>> roots = new ArrayList<>();
        for (PlacementsHolderLocation<?> selection : selectionSet) {
            if (!hasSelectedAncestor(selection, selectionSet)) {
                roots.add(selection);
            }
        }
        return roots;
    }

    private boolean hasSelectedAncestor(PlacementsHolderLocation<?> location,
            Set<PlacementsHolderLocation<?>> selectionSet) {
        PanelLocation parent = location.getParent();
        while (parent != null) {
            if (selectionSet.contains(parent)) {
                return true;
            }
            parent = parent.getParent();
        }
        return false;
    }

    private Actuator findActuatorById(String actuatorId) {
        if (actuatorId == null || actuatorId.isEmpty()) {
            return null;
        }
        for (Actuator actuator : machine.getAllActuators()) {
            if (Objects.equals(actuator.getId(), actuatorId)) {
                return actuator;
            }
        }
        return null;
    }

    private JComboBox<String> createEditableProfileBox() {
        JComboBox<String> comboBox = new JComboBox<>();
        comboBox.setEditable(true);
        return comboBox;
    }

    private void updateProfileChoices(JComboBox<String> comboBox, Actuator actuator,
            String selectedValue) {
        comboBox.removeAllItems();
        comboBox.addItem("");
        if (actuator != null) {
            for (String value : actuator.getProfileValues()) {
                comboBox.addItem(value);
            }
        }
        comboBox.setSelectedItem(selectedValue == null ? "" : selectedValue);
    }

    private String getComboValue(JComboBox<String> comboBox) {
        Object item = comboBox.getEditor().getItem();
        if (item == null) {
            item = comboBox.getSelectedItem();
        }
        return item == null ? "" : item.toString().trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private double parseDouble(String value, String description) throws Exception {
        try {
            return Double.parseDouble(value.trim());
        }
        catch (Exception e) {
            throw new Exception("Invalid " + description + ": " + value, e);
        }
    }

    private int parseInt(String value, String description) throws Exception {
        try {
            return Integer.parseInt(value.trim());
        }
        catch (Exception e) {
            throw new Exception("Invalid " + description + ": " + value, e);
        }
    }

    private void addField(JPanel panel, int row, String label, JComponent component) {
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = row;
        labelConstraints.anchor = GridBagConstraints.LINE_END;
        labelConstraints.insets = new Insets(4, 4, 4, 8);
        panel.add(new JLabel(label), labelConstraints);

        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.gridx = 1;
        fieldConstraints.gridy = row;
        fieldConstraints.weightx = 1.0;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        fieldConstraints.insets = new Insets(4, 0, 4, 4);
        panel.add(component, fieldConstraints);
    }
}
