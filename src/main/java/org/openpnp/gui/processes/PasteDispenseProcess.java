package org.openpnp.gui.processes;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
import org.openpnp.model.Abstract2DLocatable.Side;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.BoardPad;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Pad;
import org.openpnp.model.PanelLocation;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;
import org.openpnp.model.PlacementsHolderLocation;
import org.openpnp.spi.Actuator;
import org.openpnp.spi.Actuator.ActuatorValueType;
import org.openpnp.spi.FiducialLocator;
import org.openpnp.spi.Machine;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.TravellingSalesman;
import org.openpnp.util.UiUtils;
import org.openpnp.util.Utils2D;
import org.pmw.tinylog.Logger;

public class PasteDispenseProcess {
    private static final String PROPERTY_NAME = "PasteDispenseProcessProperties";

    private final MainFrame mainFrame;
    private final JobPanel jobPanel;
    private final Machine machine;
    private final PasteDispenseProcessProperties properties;
    private final Set<String> placementIdFilter;
    private Path debugOutputDirectory;

    public static class PasteDispenseProcessProperties {
        public String actuatorId = "";
        public boolean runFiducialCheck = true;
        public boolean dryRun = false;
        public double dispenseZOffsetMm = 0.0;
        public double intraPadLiftMm = 5.0;
        public int postDispenseDwellMs = 0;
        public double dotAreaMm2 = 0.5;
        public boolean writeDebugPlots = false;
    }

    private static class PastePadTarget {
        private final BoardLocation boardLocation;
        private final BoardPad pad;
        private final Location travelLocation;
        private final List<Location> dotLocations;
        private final double areaMm2;

        private PastePadTarget(BoardLocation boardLocation, BoardPad pad, Location travelLocation,
                List<Location> dotLocations, double areaMm2) {
            this.boardLocation = boardLocation;
            this.pad = pad;
            this.travelLocation = travelLocation;
            this.dotLocations = dotLocations;
            this.areaMm2 = areaMm2;
        }

        private String getDisplayName() {
            if (pad.getName() != null && !pad.getName().isEmpty()) {
                return pad.getName();
            }
            return boardLocation.getUniqueId();
        }
    }

    static class LocalDot {
        final double x;
        final double y;

        private LocalDot(double x, double y) {
            this.x = x;
            this.y = y;
        }

        private double distanceSquared(LocalDot other) {
            double dx = x - other.x;
            double dy = y - other.y;
            return (dx * dx) + (dy * dy);
        }
    }

    private static class HexLayout {
        private final double pitch;
        private final List<LocalDot> dots;
        private final int centerLineDots;

        private HexLayout(double pitch, List<LocalDot> dots, int centerLineDots) {
            this.pitch = pitch;
            this.dots = dots;
            this.centerLineDots = centerLineDots;
        }
    }

    public PasteDispenseProcess(MainFrame mainFrame, JobPanel jobPanel) throws Exception {
        this(mainFrame, jobPanel, null);
    }

    public PasteDispenseProcess(MainFrame mainFrame, JobPanel jobPanel,
            List<String> placementIds) throws Exception {
        this.mainFrame = mainFrame;
        this.jobPanel = jobPanel;
        this.machine = Configuration.get().getMachine();
        this.placementIdFilter = placementIds == null ? null : new LinkedHashSet<>(placementIds);
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

        JCheckBox dryRunCheckBox = new JCheckBox();
        dryRunCheckBox.setSelected(properties.dryRun);

        JTextField zOffsetField = new JTextField(
                String.format(Locale.US, "%.3f", properties.dispenseZOffsetMm), 10);
        JTextField intraPadLiftField = new JTextField(
                String.format(Locale.US, "%.3f", properties.intraPadLiftMm), 10);
        JTextField dwellField =
                new JTextField(Integer.toString(properties.postDispenseDwellMs), 10);
        JTextField dotAreaField =
                new JTextField(String.format(Locale.US, "%.3f", properties.dotAreaMm2), 10);
        JCheckBox debugPlotsCheckBox = new JCheckBox();
        debugPlotsCheckBox.setSelected(properties.writeDebugPlots);

        JPanel panel = new JPanel(new GridBagLayout());
        int row = 0;
        addField(panel, row++, "Actuator", actuatorBox);
        addField(panel, row++, "Run fiducial check first", fiducialCheckBox);
        addField(panel, row++, "Dry run (move only, no actuation)", dryRunCheckBox);
        addField(panel, row++, "Dispense Z offset (mm)", zOffsetField);
        addField(panel, row++, "Raise between dots on one pad (mm)", intraPadLiftField);
        addField(panel, row++, "Post-dispense dwell (ms)", dwellField);
        addField(panel, row++, "Dot area per actuation (mm^2)", dotAreaField);
        addField(panel, row++, "Write debug pad plots", debugPlotsCheckBox);

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
        properties.dryRun = dryRunCheckBox.isSelected();
        properties.dispenseZOffsetMm = parseDouble(zOffsetField.getText(), "dispense Z offset");
        properties.intraPadLiftMm =
                parseDouble(intraPadLiftField.getText(), "raise between dots on one pad");
        if (properties.intraPadLiftMm < 0) {
            throw new Exception("Raise between dots on one pad must be zero or greater.");
        }
        properties.postDispenseDwellMs = parseInt(dwellField.getText(), "post-dispense dwell");
        if (properties.postDispenseDwellMs < 0) {
            throw new Exception("Post-dispense dwell must be zero or greater.");
        }
        properties.dotAreaMm2 = parseDouble(dotAreaField.getText(), "dot area per actuation");
        if (properties.dotAreaMm2 <= 0) {
            throw new Exception("Dot area per actuation must be greater than zero.");
        }
        properties.writeDebugPlots = debugPlotsCheckBox.isSelected();
        machine.setProperty(PROPERTY_NAME, properties);
        return true;
    }

    private void run() throws Exception {
        debugOutputDirectory = properties.writeDebugPlots ? prepareDebugOutputDirectory() : null;
        Actuator actuator = requireDispenseActuator();
        List<PlacementsHolderLocation<?>> selectedLocations;
        if (placementIdFilter != null) {
            PlacementsHolderLocation<?> boardOrPanelLocation =
                    jobPanel.getJobPlacementsPanel().getBoardOrPanelLocation();
            if (boardOrPanelLocation == null) {
                throw new Exception("No board is selected in the placements panel.");
            }
            selectedLocations = new ArrayList<>();
            selectedLocations.add(boardOrPanelLocation);
        }
        else {
            selectedLocations = pruneNestedSelections(jobPanel.getSelections());
            if (selectedLocations.isEmpty()) {
                throw new Exception("Select at least one board or panel in the Job tab.");
            }
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
                dispensedDots = dispensePad(actuator, target, dispensedDots);
            }
        }

        actuator.getHead().moveToSafeZ();
        jobPanel.refresh();

        if (!foundAnyPads) {
            throw new Exception("No paste pads were found. Ensure placements are enabled and their packages have footprints defined.");
        }

        mainFrame.setStatus(String.format(Locale.US,
                "Paste %s complete. %s %d dot(s).",
                properties.dryRun ? "dry run" : "dispensing",
                properties.dryRun ? "Visited" : "Dispensed",
                dispensedDots));
        if (debugOutputDirectory != null) {
            Logger.info("Paste debug plots written to {}", debugOutputDirectory.toAbsolutePath());
        }
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
            for (Placement placement : boardLocation.getBoard().getPlacements()) {
                if (!placement.isEnabled()) {
                    continue;
                }
                if (placement.getType() != Placement.Type.Placement) {
                    continue;
                }
                if (placement.getSide() != boardLocation.getGlobalSide()) {
                    continue;
                }
                if (placementIdFilter != null && !placementIdFilter.contains(placement.getId())) {
                    continue;
                }
                Part part = placement.getPart();
                if (part == null || part.getPackage() == null) {
                    continue;
                }
                Footprint footprint = part.getPackage().getFootprint();
                if (footprint == null || footprint.getPads().isEmpty()) {
                    continue;
                }
                for (Footprint.Pad fp : footprint.getPads()) {
                    BoardPad boardPad = footprintPadToBoardPad(placement, footprint, fp);
                    double areaMm2 = calculatePadAreaMm2(boardPad);
                    List<Location> dotLocations = calculatePadLocations(boardLocation, boardPad);
                    targets.add(new PastePadTarget(boardLocation, boardPad,
                            calculatePadLocation(boardLocation, boardPad, 0.0, 0.0), dotLocations,
                            areaMm2));
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

    private BoardPad footprintPadToBoardPad(Placement placement, Footprint footprint,
            Footprint.Pad fp) {
        Location padLocation = createPlacementLocalPadLocation(placement, footprint, fp);
        Pad padShape = createPadShape(footprint, fp);

        String name = placement.getId();
        if (fp.getName() != null && !fp.getName().isEmpty()) {
            name += "." + fp.getName();
        }
        BoardPad boardPad = new BoardPad(padShape, padLocation);
        boardPad.setName(name);
        boardPad.setSide(placement.getSide());
        return boardPad;
    }

    static Location createPlacementLocalPadLocation(Placement placement, Footprint footprint,
            Footprint.Pad fp) {
        double scale = Length.convertToUnits(1.0, footprint.getUnits(), LengthUnit.Millimeters);
        double fpXmm = fp.getX() * scale;
        double fpYmm = fp.getY() * scale;

        // Footprint pads are defined as seen from the component side. For bottom-side components
        // the component side is the mirror of the board-local (top-view) coordinate space, so
        // the X offset must be negated to get board-local coordinates.
        boolean isBottom = placement.getSide() == Side.Bottom;
        if (isBottom) {
            fpXmm = -fpXmm;
        }

        Location placementLoc = placement.getLocation().convertToUnits(LengthUnit.Millimeters);
        Location localPadLocation = new Location(LengthUnit.Millimeters, fpXmm, fpYmm,
                placementLoc.getZ(), isBottom ? -fp.getRotation() : fp.getRotation());
        return localPadLocation.rotateXy(-placementLoc.getRotation()).addWithRotation(placementLoc);
    }

    static Pad createPadShape(Footprint footprint, Footprint.Pad fp) {
        double scale = Length.convertToUnits(1.0, footprint.getUnits(), LengthUnit.Millimeters);
        double fpWidthMm = fp.getWidth() * scale;
        double fpHeightMm = fp.getHeight() * scale;
        Pad padShape;
        if (fp.getRoundness() >= 100.0 && Math.abs(fpWidthMm - fpHeightMm) < 1e-6) {
            Pad.Circle circle = new Pad.Circle();
            circle.setUnits(LengthUnit.Millimeters);
            circle.setRadius(fpWidthMm / 2.0);
            padShape = circle;
        }
        else {
            Pad.RoundRectangle rr = new Pad.RoundRectangle();
            rr.setUnits(LengthUnit.Millimeters);
            rr.setWidth(fpWidthMm);
            rr.setHeight(fpHeightMm);
            // Pad.RoundRectangle arc = width * roundness. We want arc = min(w,h) * fp_roundness/100.
            if (fpWidthMm > 0) {
                rr.setRoundness(Math.min(fpWidthMm, fpHeightMm) / fpWidthMm * fp.getRoundness() / 100.0);
            }
            padShape = rr;
        }
        return padShape;
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
        double areaMm2 = calculatePadAreaMm2(pad);
        int targetDotCount = Math.max(1, (int) Math.round(areaMm2 / properties.dotAreaMm2));
        List<LocalDot> localDots = calculateLocalDots(shape, bounds, areaMm2, targetDotCount);

        List<Location> locations = new ArrayList<>();
        for (LocalDot localDot : localDots) {
            locations.add(calculatePadLocation(boardLocation, pad, localDot.x, localDot.y));
        }

        if (locations.isEmpty()) {
            locations.add(calculatePadLocation(boardLocation, pad, 0.0, 0.0));
        }
        writePadDebugPlot(boardLocation, pad, mmPad, localDots, locations, areaMm2, targetDotCount);
        return locations;
    }

    static List<LocalDot> calculateLocalDots(Pad mmPad, double dotAreaMm2) {
        Shape shape = mmPad.getShape();
        Rectangle2D bounds = shape.getBounds2D();
        double areaMm2 = calculatePadAreaMm2(mmPad);
        int targetDotCount = Math.max(1, (int) Math.round(areaMm2 / dotAreaMm2));
        return calculateLocalDots(shape, bounds, areaMm2, targetDotCount);
    }

    private static double calculatePadAreaMm2(Pad mmPad) {
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

    static List<Location> calculateLocalDotLocations(Pad mmPad, double dotAreaMm2) {
        List<Location> locations = new ArrayList<>();
        for (LocalDot dot : calculateLocalDots(mmPad, dotAreaMm2)) {
            locations.add(new Location(LengthUnit.Millimeters, dot.x, dot.y, 0, 0));
        }
        return locations;
    }

    private static List<LocalDot> calculateLocalDots(Shape shape, Rectangle2D bounds, double areaMm2,
            int targetDotCount) {
        LocalDot centerDot = calculateCenterDot(shape, bounds);
        if (targetDotCount <= 1) {
            List<LocalDot> dots = new ArrayList<>();
            dots.add(centerDot);
            return dots;
        }

        boolean longAxisX = bounds.getWidth() >= bounds.getHeight();
        double nominalPitch =
                Math.sqrt((2.0 * areaMm2) / (Math.sqrt(3.0) * Math.max(1, targetDotCount)));
        HexLayout layout = findBestHexLayout(shape, targetDotCount, nominalPitch, longAxisX, bounds);
        if (layout == null || layout.dots.isEmpty()) {
            List<LocalDot> dots = new ArrayList<>();
            dots.add(centerDot);
            return dots;
        }

        List<LocalDot> dots = new ArrayList<>(layout.dots);
        if (dots.size() > targetDotCount) {
            dots = new ArrayList<>(dots.subList(0, targetDotCount));
        }
        if (dots.isEmpty()) {
            dots.add(centerDot);
        }
        return dots;
    }

    private static HexLayout findBestHexLayout(Shape shape, int targetDotCount, double nominalPitch,
            boolean longAxisX, Rectangle2D bounds) {
        HexLayout bestLayout = null;
        double minPitch = nominalPitch * 0.35;
        double maxPitch = nominalPitch * 2.50;
        for (int i = 0; i < 48; i++) {
            double t = i / 47.0;
            double pitch = minPitch * Math.pow(maxPitch / minPitch, t);
            HexLayout candidate = generateAxisHexLayout(shape, bounds, pitch, longAxisX);
            bestLayout = chooseBetterLayout(bestLayout, candidate, targetDotCount);
        }
        return bestLayout;
    }

    private static HexLayout generateAxisHexLayout(Shape shape, Rectangle2D bounds, double pitch,
            boolean longAxisX) {
        if (!(pitch > 0.0)) {
            return null;
        }

        List<LocalDot> dots = new ArrayList<>();
        int centerLineDots = addAxisLineDots(shape, bounds, longAxisX, 0.0, 0.0, pitch, dots);
        double rowPitch = pitch * Math.sqrt(3.0) / 2.0;
        double minorHalfSpan = longAxisX ? bounds.getHeight() / 2.0 : bounds.getWidth() / 2.0;
        int maxRows = Math.max(1, (int) Math.ceil((minorHalfSpan + rowPitch) / rowPitch));
        for (int row = 1; row <= maxRows; row++) {
            double v = row * rowPitch;
            double phase = ((row & 1) == 1) ? (pitch / 2.0) : 0.0;
            boolean addedPositive = addAxisLineDots(shape, bounds, longAxisX, v, phase, pitch,
                    dots) > 0;
            boolean addedNegative = addAxisLineDots(shape, bounds, longAxisX, -v, phase, pitch,
                    dots) > 0;
            if (!addedPositive && !addedNegative && v > minorHalfSpan) {
                break;
            }
        }
        return new HexLayout(pitch, dots, centerLineDots);
    }

    private static int addAxisLineDots(Shape shape, Rectangle2D bounds, boolean longAxisX,
            double minorOffset, double phase, double pitch, List<LocalDot> dots) {
        double halfMajorSpan = longAxisX ? bounds.getWidth() / 2.0 : bounds.getHeight() / 2.0;
        int dotsBefore = dots.size();

        if (Math.abs(phase) < 1e-9) {
            addAxisDot(shape, longAxisX, 0.0, minorOffset, dots);
        }

        for (int index = 0;; index++) {
            double majorOffset = phase + (index * pitch);
            if (majorOffset > halfMajorSpan + pitch) {
                break;
            }
            if (majorOffset > 1e-9) {
                addAxisDot(shape, longAxisX, majorOffset, minorOffset, dots);
                addAxisDot(shape, longAxisX, -majorOffset, minorOffset, dots);
            }
        }

        return dots.size() - dotsBefore;
    }

    private static void addAxisDot(Shape shape, boolean longAxisX, double majorOffset,
            double minorOffset, List<LocalDot> dots) {
        LocalDot dot = longAxisX ? new LocalDot(majorOffset, minorOffset)
                : new LocalDot(minorOffset, majorOffset);
        if (shape.contains(dot.x, dot.y)) {
            dots.add(dot);
        }
    }

    private static HexLayout chooseBetterLayout(HexLayout currentBest, HexLayout candidate,
            int targetDotCount) {
        if (candidate == null) {
            return currentBest;
        }
        if (currentBest == null) {
            return candidate;
        }

        int currentDifference = Math.abs(currentBest.dots.size() - targetDotCount);
        int candidateDifference = Math.abs(candidate.dots.size() - targetDotCount);
        if (candidateDifference < currentDifference) {
            return candidate;
        }
        if (candidateDifference > currentDifference) {
            return currentBest;
        }

        boolean candidateMeetsTarget = candidate.dots.size() >= targetDotCount;
        boolean currentMeetsTarget = currentBest.dots.size() >= targetDotCount;
        if (candidateMeetsTarget && !currentMeetsTarget) {
            return candidate;
        }
        if (!candidateMeetsTarget && currentMeetsTarget) {
            return currentBest;
        }
        if (candidate.centerLineDots > currentBest.centerLineDots) {
            return candidate;
        }
        if (candidate.centerLineDots < currentBest.centerLineDots) {
            return currentBest;
        }
        return candidate.dots.size() < currentBest.dots.size() ? candidate : currentBest;
    }

    private static LocalDot calculateCenterDot(Shape shape, Rectangle2D bounds) {
        double centerX = bounds.getCenterX();
        double centerY = bounds.getCenterY();
        if (shape.contains(centerX, centerY)) {
            return new LocalDot(centerX, centerY);
        }

        double maxRadius = Math.max(bounds.getWidth(), bounds.getHeight());
        for (int ring = 1; ring <= 20; ring++) {
            double radius = (maxRadius * ring) / 40.0;
            for (int step = 0; step < 24; step++) {
                double angle = (2.0 * Math.PI * step) / 24.0;
                double x = centerX + (Math.cos(angle) * radius);
                double y = centerY + (Math.sin(angle) * radius);
                if (shape.contains(x, y)) {
                    return new LocalDot(x, y);
                }
            }
        }
        return new LocalDot(centerX, centerY);
    }

    private Location calculatePadLocation(BoardLocation boardLocation, BoardPad pad, double localX,
            double localY) {
        Location padLocation = pad.getLocation().convertToUnits(LengthUnit.Millimeters);
        Location pointLocation = new Location(LengthUnit.Millimeters, localX, localY, 0, 0)
                .offsetWithRotationFrom(padLocation)
                .derive(null, null, padLocation.getZ(), padLocation.getRotation());

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

    private int dispensePad(Actuator actuator, PastePadTarget target, int dispensedDots)
            throws Exception {
        for (int i = 0; i < target.dotLocations.size(); i++) {
            Location dotLocation = target.dotLocations.get(i);
            if (i == 0) {
                MovableUtils.moveToLocationAtSafeZ(actuator, dotLocation);
            }
            else {
                moveWithinPad(actuator, dotLocation);
            }

            int currentDotNumber = i + 1;
            int totalDispensedDots = dispensedDots + currentDotNumber;
            mainFrame.setStatus(String.format(Locale.US,
                    "%s paste %d: %s on %s dot %d/%d (%.3f mm^2 pad)",
                    properties.dryRun ? "Dry run" : "Dispensing",
                    totalDispensedDots,
                    target.getDisplayName(), target.boardLocation.getUniqueId(),
                    currentDotNumber, target.dotLocations.size(), target.areaMm2));
            Logger.info(String.format(Locale.US,
                    "%s paste %d: %s on %s dot %d/%d (%.3f mm^2 pad)",
                    properties.dryRun ? "Dry run" : "Dispensing",
                    totalDispensedDots,
                    target.getDisplayName(), target.boardLocation.getUniqueId(),
                    currentDotNumber, target.dotLocations.size(), target.areaMm2));
            if (!properties.dryRun) {
                actuator.actuate(true);
                actuator.actuate(false);
            }
            if (properties.postDispenseDwellMs > 0) {
                actuator.delay(properties.postDispenseDwellMs, actuator);
            }
        }

        return dispensedDots + target.dotLocations.size();
    }

    private void moveWithinPad(Actuator actuator, Location targetLocation) throws Exception {
        if (properties.intraPadLiftMm <= 0) {
            actuator.moveTo(targetLocation);
            return;
        }

        Location currentLocation = actuator.getLocation().convertToUnits(targetLocation.getUnits());
        double lift = Length.convertToUnits(properties.intraPadLiftMm, LengthUnit.Millimeters,
                targetLocation.getUnits());
        double clearanceZ = targetLocation.getZ() + lift;

        actuator.moveTo(currentLocation.derive(null, null, clearanceZ, targetLocation.getRotation()));
        actuator.moveTo(targetLocation.derive(null, null, clearanceZ, null));
        actuator.moveTo(targetLocation);
    }

    private void optimizeTravel(Actuator actuator, List<PastePadTarget> targets) {
        if (targets.size() < 2) {
            return;
        }
        TravellingSalesman<PastePadTarget> travellingSalesman = new TravellingSalesman<>(targets,
                target -> target.travelLocation, actuator.getLocation(), null, actuator);
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

    private Path prepareDebugOutputDirectory() throws Exception {
        try {
            Path baseDir = Path.of(System.getProperty("java.io.tmpdir"), "openpnp-paste-debug");
            Files.createDirectories(baseDir);
            Path runDir = baseDir.resolve(String.format(Locale.US, "run-%d", System.currentTimeMillis()));
            Files.createDirectories(runDir);
            Logger.info("Paste debug plots enabled: {}", runDir.toAbsolutePath());
            return runDir;
        }
        catch (IOException e) {
            throw new Exception("Unable to create paste debug output directory.", e);
        }
    }

    private void writePadDebugPlot(BoardLocation boardLocation, BoardPad pad, Pad mmPad,
            List<LocalDot> localDots, List<Location> boardDots, double areaMm2, int targetDotCount)
            throws Exception {
        if (debugOutputDirectory == null) {
            return;
        }

        String safeName = sanitizeFileName(String.format(Locale.US, "%s__%s",
                boardLocation.getUniqueId(), pad.getName() == null ? "pad" : pad.getName()));
        Path svgPath = debugOutputDirectory.resolve(safeName + ".svg");
        Path boardSvgPath = debugOutputDirectory.resolve(safeName + "__board.svg");
        Path csvPath = debugOutputDirectory.resolve(safeName + ".csv");

        Rectangle2D bounds = mmPad.getShape().getBounds2D();
        double margin = Math.max(0.5, Math.max(bounds.getWidth(), bounds.getHeight()) * 0.15);
        double minX = bounds.getMinX() - margin;
        double minY = bounds.getMinY() - margin;
        double width = bounds.getWidth() + margin * 2.0;
        double height = bounds.getHeight() + margin * 2.0;

        try (BufferedWriter writer = Files.newBufferedWriter(svgPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write(String.format(Locale.US,
                    "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"%.6f %.6f %.6f %.6f\">%n",
                    minX, -minY - height, width, height));
            writer.write("<rect width=\"100%\" height=\"100%\" fill=\"white\"/>");
            writer.write(String.format(Locale.US,
                    "<path d=\"%s\" fill=\"none\" stroke=\"#111\" stroke-width=\"0.05\"/>%n",
                    shapeToSvgPath(mmPad.getShape())));
            for (int i = 0; i < localDots.size(); i++) {
                LocalDot dot = localDots.get(i);
                writer.write(String.format(Locale.US,
                        "<circle cx=\"%.6f\" cy=\"%.6f\" r=\"0.12\" fill=\"#d7263d\"/>%n",
                        dot.x, -dot.y));
                writer.write(String.format(Locale.US,
                        "<text x=\"%.6f\" y=\"%.6f\" font-size=\"0.35\" fill=\"#1f4e79\">%d</text>%n",
                        dot.x + 0.15, -dot.y - 0.15, i + 1));
            }
            writer.write("</svg>\n");
        }
        catch (IOException e) {
            throw new Exception("Unable to write paste debug SVG: " + svgPath, e);
        }

        Shape boardShape = transformPadShapeToBoard(boardLocation, pad, mmPad);
        Rectangle2D boardBounds = boardShape.getBounds2D();
        double boardMargin = Math.max(0.5,
                Math.max(boardBounds.getWidth(), boardBounds.getHeight()) * 0.15);
        double boardMinX = boardBounds.getMinX() - boardMargin;
        double boardMinY = boardBounds.getMinY() - boardMargin;
        double boardWidth = boardBounds.getWidth() + boardMargin * 2.0;
        double boardHeight = boardBounds.getHeight() + boardMargin * 2.0;

        try (BufferedWriter writer = Files.newBufferedWriter(boardSvgPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write(String.format(Locale.US,
                    "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"%.6f %.6f %.6f %.6f\">%n",
                    boardMinX, -boardMinY - boardHeight, boardWidth, boardHeight));
            writer.write("<rect width=\"100%\" height=\"100%\" fill=\"white\"/>");
            writer.write(String.format(Locale.US,
                    "<path d=\"%s\" fill=\"none\" stroke=\"#111\" stroke-width=\"0.05\"/>%n",
                    shapeToSvgPath(boardShape)));
            for (int i = 0; i < boardDots.size(); i++) {
                Location boardDot = boardDots.get(i).convertToUnits(LengthUnit.Millimeters);
                writer.write(String.format(Locale.US,
                        "<circle cx=\"%.6f\" cy=\"%.6f\" r=\"0.12\" fill=\"#1f4e79\"/>%n",
                        boardDot.getX(), -boardDot.getY()));
                writer.write(String.format(Locale.US,
                        "<text x=\"%.6f\" y=\"%.6f\" font-size=\"0.35\" fill=\"#d7263d\">%d</text>%n",
                        boardDot.getX() + 0.15, -boardDot.getY() - 0.15, i + 1));
            }
            writer.write("</svg>\n");
        }
        catch (IOException e) {
            throw new Exception("Unable to write paste debug board SVG: " + boardSvgPath, e);
        }

        try (BufferedWriter writer = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write("board_id,pad_name,pad_rotation_deg,pad_area_mm2,target_dot_count,dot_index,local_x_mm,local_y_mm,board_x_mm,board_y_mm,board_rotation_deg\n");
            Location padLocation = pad.getLocation().convertToUnits(LengthUnit.Millimeters);
            for (int i = 0; i < localDots.size(); i++) {
                LocalDot localDot = localDots.get(i);
                Location boardDot = boardDots.get(i).convertToUnits(LengthUnit.Millimeters);
                writer.write(String.format(Locale.US,
                        "\"%s\",\"%s\",%.6f,%.6f,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f%n",
                        boardLocation.getUniqueId(),
                        pad.getName() == null ? "" : pad.getName(),
                        padLocation.getRotation(), areaMm2, targetDotCount, i + 1, localDot.x,
                        localDot.y, boardDot.getX(), boardDot.getY(), boardDot.getRotation()));
            }
        }
        catch (IOException e) {
            throw new Exception("Unable to write paste debug CSV: " + csvPath, e);
        }
    }

    private String sanitizeFileName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private Shape transformPadShapeToBoard(BoardLocation boardLocation, BoardPad pad, Pad mmPad) {
        Location padLocation = pad.getLocation().convertToUnits(LengthUnit.Millimeters);
        AffineTransform padTransform = new AffineTransform();
        padTransform.translate(padLocation.getX(), padLocation.getY());
        padTransform.rotate(Math.toRadians(padLocation.getRotation()));
        Shape boardLocalShape = padTransform.createTransformedShape(mmPad.getShape());

        AffineTransform boardTransform = boardLocation.getLocalToGlobalTransform();
        if (boardTransform == null) {
            boardTransform = Utils2D.getDefaultBoardPlacementLocationTransform(boardLocation);
        }
        return boardTransform.createTransformedShape(boardLocalShape);
    }

    private String shapeToSvgPath(Shape shape) {
        StringBuilder builder = new StringBuilder();
        PathIterator iterator = shape.getPathIterator(null, 0.02);
        double[] coords = new double[6];
        while (!iterator.isDone()) {
            int type = iterator.currentSegment(coords);
            switch (type) {
                case PathIterator.SEG_MOVETO:
                    builder.append(String.format(Locale.US, "M %.6f %.6f ", coords[0], -coords[1]));
                    break;
                case PathIterator.SEG_LINETO:
                    builder.append(String.format(Locale.US, "L %.6f %.6f ", coords[0], -coords[1]));
                    break;
                case PathIterator.SEG_QUADTO:
                    builder.append(String.format(Locale.US, "Q %.6f %.6f %.6f %.6f ", coords[0],
                            -coords[1], coords[2], -coords[3]));
                    break;
                case PathIterator.SEG_CUBICTO:
                    builder.append(String.format(Locale.US,
                            "C %.6f %.6f %.6f %.6f %.6f %.6f ", coords[0], -coords[1], coords[2],
                            -coords[3], coords[4], -coords[5]));
                    break;
                case PathIterator.SEG_CLOSE:
                    builder.append("Z ");
                    break;
                default:
                    break;
            }
            iterator.next();
        }
        return builder.toString().trim();
    }
}
