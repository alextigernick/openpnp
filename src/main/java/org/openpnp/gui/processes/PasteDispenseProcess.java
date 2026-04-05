package org.openpnp.gui.processes;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Shape;
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
        public double dotAreaMm2 = 0.5;
    }

    private static class PastePadTarget {
        private final BoardLocation boardLocation;
        private final BoardPad pad;
        private final Location location;
        private final double areaMm2;
        private final int dotNumber;
        private final int dotCount;

        private PastePadTarget(BoardLocation boardLocation, BoardPad pad, Location location,
                double areaMm2, int dotNumber, int dotCount) {
            this.boardLocation = boardLocation;
            this.pad = pad;
            this.location = location;
            this.areaMm2 = areaMm2;
            this.dotNumber = dotNumber;
            this.dotCount = dotCount;
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
                    String scope =
                            actuator.getHead() == null ? "Machine" : actuator.getHead().getName();
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
        JTextField dotAreaField =
                new JTextField(String.format(Locale.US, "%.3f", properties.dotAreaMm2), 10);

        JPanel panel = new JPanel(new GridBagLayout());
        int row = 0;
        addField(panel, row++, "Actuator", actuatorBox);
        addField(panel, row++, "Run fiducial check first", fiducialCheckBox);
        addField(panel, row++, "Dispense Z offset (mm)", zOffsetField);
        addField(panel, row++, "Post-dispense dwell (ms)", dwellField);
        addField(panel, row++, "Dot area per actuation (mm^2)", dotAreaField);

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
        properties.dotAreaMm2 = parseDouble(dotAreaField.getText(), "dot area per actuation");
        if (properties.dotAreaMm2 <= 0) {
            throw new Exception("Dot area per actuation must be greater than zero.");
        }
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

        int dispensedDots = 0;
        boolean foundAnyPads = false;
        for (PlacementsHolderLocation<?> selectedLocation : selectedLocations) {
            if (!selectedLocation.isEnabled()) {
                continue;
            }

            if (properties.runFiducialCheck) {
                locateFiducials(selectedLocation);
            }

            List<PastePadTarget> targets = collectTargets(selectedLocation);
            if (targets.isEmpty()) {
                continue;
            }
            foundAnyPads = true;

            optimizeTravel(actuator, targets);
            for (PastePadTarget target : targets) {
                dispensedDots++;
                mainFrame.setStatus(String.format(Locale.US,
                        "Dispensing paste %d: %s on %s dot %d/%d (%.3f mm^2 pad)", dispensedDots,
                        target.getDisplayName(), target.boardLocation.getUniqueId(),
                        target.dotNumber, target.dotCount, target.areaMm2));
                MovableUtils.moveToLocationAtSafeZ(actuator, target.location);
                Logger.info(String.format(Locale.US,
                        "Dispensing paste %d: %s on %s dot %d/%d (%.3f mm^2 pad)", dispensedDots,
                        target.getDisplayName(), target.boardLocation.getUniqueId(),
                        target.dotNumber, target.dotCount, target.areaMm2));
                actuator.actuate(true);
                actuator.actuate(false);
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
                "Paste dispensing complete. Dispensed %d dot(s).", dispensedDots));
    }

    private Actuator requireDispenseActuator() throws Exception {
        Actuator actuator = findActuatorById(properties.actuatorId);
        if (actuator == null) {
            throw new Exception("The configured paste actuator could not be found.");
        }
        if (actuator.getHead() == null) {
            throw new Exception("The selected paste actuator must be mounted on a head.");
        }
        if (actuator.getValueType() != ActuatorValueType.Boolean) {
            throw new Exception(
                    "The selected paste actuator must use Boolean values so each dot is one actuation pulse.");
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
            return;
        }

        if (location instanceof BoardLocation) {
            BoardLocation boardLocation = (BoardLocation) location;
            for (BoardPad pad : boardLocation.getBoard().getSolderPastePads()) {
                if (pad == null || pad.getPad() == null || pad.getType() != BoardPad.Type.Paste) {
                    continue;
                }
                if (pad.getSide() != boardLocation.getGlobalSide()) {
                    continue;
                }
                double areaMm2 = calculatePadAreaMm2(pad);
                List<Location> dotLocations = calculatePadLocations(boardLocation, pad);
                for (int i = 0; i < dotLocations.size(); i++) {
                    targets.add(new PastePadTarget(boardLocation, pad, dotLocations.get(i), areaMm2,
                            i + 1, dotLocations.size()));
                }
            }
        }
        else if (location instanceof PanelLocation) {
            for (PlacementsHolderLocation<?> child : ((PanelLocation) location).getPanel()
                    .getChildren()) {
                collectTargets(child, targets);
            }
        }
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

    private List<Location> calculatePadLocations(BoardLocation boardLocation, BoardPad pad)
            throws Exception {
        Pad mmPad = pad.getPad().convertToUnits(LengthUnit.Millimeters);
        Shape shape = mmPad.getShape();
        Rectangle2D bounds = shape.getBounds2D();
        double spacingMm = Math.sqrt(properties.dotAreaMm2);
        int columns = Math.max(1, (int) Math.ceil(bounds.getWidth() / spacingMm));
        int rows = Math.max(1, (int) Math.ceil(bounds.getHeight() / spacingMm));

        List<Location> locations = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            double localY =
                    rows == 1 ? 0.0 : bounds.getMinY() + ((row + 0.5) * bounds.getHeight() / rows);
            for (int column = 0; column < columns; column++) {
                double localX = columns == 1 ? 0.0
                        : bounds.getMinX() + ((column + 0.5) * bounds.getWidth() / columns);
                if (!shape.contains(localX, localY)) {
                    continue;
                }
                locations.add(calculatePadLocation(boardLocation, pad, localX, localY));
            }
        }

        if (locations.isEmpty()) {
            locations.add(calculatePadLocation(boardLocation, pad, 0.0, 0.0));
        }
        return locations;
    }

    private Location calculatePadLocation(BoardLocation boardLocation, BoardPad pad, double localX,
            double localY) {
        Location padLocation = pad.getLocation().convertToUnits(LengthUnit.Millimeters);
        Location localOffset = new Location(LengthUnit.Millimeters, localX, localY, 0, 0)
                .rotateXy(padLocation.getRotation());
        Location pointLocation = padLocation.add(localOffset).derive(null, null, padLocation.getZ(),
                padLocation.getRotation());

        Placement placement = new Placement(
                pad.getName() == null || pad.getName().isEmpty() ? "Paste Pad" : pad.getName());
        placement.removePropertyChangeListener(placement);
        placement.setLocation(pointLocation);

        Location location = org.openpnp.util.Utils2D.calculateBoardPlacementLocation(boardLocation,
                placement);
        double zOffset = Length.convertToUnits(properties.dispenseZOffsetMm, LengthUnit.Millimeters,
                location.getUnits());
        return location.add(new Location(location.getUnits(), 0, 0, zOffset, 0));
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
