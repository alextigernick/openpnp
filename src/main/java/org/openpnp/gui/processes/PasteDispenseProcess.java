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
        public boolean dryRun = false;
        public double dispenseZOffsetMm = 0.0;
        public double intraPadLiftMm = 5.0;
        public int postDispenseDwellMs = 0;
        public double dotAreaMm2 = 0.5;
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

    private static class LocalDot {
        private final double x;
        private final double y;

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
        private final double centroidDistanceSquared;

        private HexLayout(double pitch, List<LocalDot> dots, double centroidDistanceSquared) {
            this.pitch = pitch;
            this.dots = dots;
            this.centroidDistanceSquared = centroidDistanceSquared;
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

        JPanel panel = new JPanel(new GridBagLayout());
        int row = 0;
        addField(panel, row++, "Actuator", actuatorBox);
        addField(panel, row++, "Run fiducial check first", fiducialCheckBox);
        addField(panel, row++, "Dry run (move only, no actuation)", dryRunCheckBox);
        addField(panel, row++, "Dispense Z offset (mm)", zOffsetField);
        addField(panel, row++, "Raise between dots on one pad (mm)", intraPadLiftField);
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
                Part part = placement.getPart();
                if (part == null || part.getPackage() == null) {
                    continue;
                }
                Footprint footprint = part.getPackage().getFootprint();
                if (footprint == null || footprint.getPads().isEmpty()) {
                    continue;
                }
                for (Footprint.Pad fp : footprint.getPads()) {
                    if (fp.getMark()) {
                        continue;
                    }
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
        double scale = Length.convertToUnits(1.0, footprint.getUnits(), LengthUnit.Millimeters);
        double fpXmm = fp.getX() * scale;
        double fpYmm = fp.getY() * scale;
        double fpWidthMm = fp.getWidth() * scale;
        double fpHeightMm = fp.getHeight() * scale;

        // Footprint pads are defined as seen from the component side. For bottom-side components
        // the component side is the mirror of the board-local (top-view) coordinate space, so
        // the X offset must be negated to get board-local coordinates.
        boolean isBottom = placement.getSide() == Side.Bottom;
        if (isBottom) {
            fpXmm = -fpXmm;
        }

        Location placementLoc = placement.getLocation().convertToUnits(LengthUnit.Millimeters);
        double theta = Math.toRadians(placementLoc.getRotation());
        double padX = placementLoc.getX() + fpXmm * Math.cos(theta) - fpYmm * Math.sin(theta);
        double padY = placementLoc.getY() + fpXmm * Math.sin(theta) + fpYmm * Math.cos(theta);
        double padRot = placementLoc.getRotation() + (isBottom ? -fp.getRotation() : fp.getRotation());
        Location padLocation = new Location(LengthUnit.Millimeters, padX, padY,
                placementLoc.getZ(), padRot);

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

        String name = placement.getId();
        if (fp.getName() != null && !fp.getName().isEmpty()) {
            name += "." + fp.getName();
        }
        BoardPad boardPad = new BoardPad(padShape, padLocation);
        boardPad.setName(name);
        boardPad.setSide(placement.getSide());
        return boardPad;
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
        return locations;
    }

    private List<LocalDot> calculateLocalDots(Shape shape, Rectangle2D bounds, double areaMm2,
            int targetDotCount) {
        LocalDot centerDot = calculateCenterDot(shape, bounds);
        if (targetDotCount <= 1) {
            List<LocalDot> dots = new ArrayList<>();
            dots.add(centerDot);
            return dots;
        }

        double nominalPitch =
                Math.sqrt((2.0 * areaMm2) / (Math.sqrt(3.0) * Math.max(1, targetDotCount)));
        HexLayout layout = findBestHexLayout(shape, bounds, centerDot, targetDotCount, nominalPitch);
        if (layout == null || layout.dots.isEmpty()) {
            List<LocalDot> dots = new ArrayList<>();
            dots.add(centerDot);
            return dots;
        }

        List<LocalDot> dots = new ArrayList<>(layout.dots);
        if (dots.size() > targetDotCount) {
            dots = selectEvenlySpacedSubset(dots, targetDotCount, centerDot);
        }
        if (dots.isEmpty()) {
            dots.add(centerDot);
        }
        return orderDotsByNearestNeighbor(dots, centerDot);
    }

    private HexLayout findBestHexLayout(Shape shape, Rectangle2D bounds, LocalDot centerDot,
            int targetDotCount, double nominalPitch) {
        HexLayout bestLayout = null;
        HexLayout denseLayout = null;
        HexLayout sparseLayout = null;

        double densePitch = nominalPitch;
        HexLayout candidate = generateBestOffsetHexLayout(shape, bounds, centerDot, densePitch,
                targetDotCount);
        bestLayout = chooseBetterLayout(bestLayout, candidate, targetDotCount);
        if (candidate != null && candidate.dots.size() >= targetDotCount) {
            denseLayout = candidate;
        }
        else {
            sparseLayout = candidate;
        }

        if (denseLayout == null) {
            double pitch = nominalPitch;
            for (int i = 0; i < 18; i++) {
                pitch *= 0.85;
                candidate = generateBestOffsetHexLayout(shape, bounds, centerDot, pitch,
                        targetDotCount);
                bestLayout = chooseBetterLayout(bestLayout, candidate, targetDotCount);
                if (candidate != null && candidate.dots.size() >= targetDotCount) {
                    denseLayout = candidate;
                    densePitch = pitch;
                    break;
                }
            }
        }
        else {
            densePitch = denseLayout.pitch;
        }

        if (sparseLayout == null) {
            double pitch = nominalPitch;
            for (int i = 0; i < 18; i++) {
                pitch /= 0.85;
                candidate = generateBestOffsetHexLayout(shape, bounds, centerDot, pitch,
                        targetDotCount);
                bestLayout = chooseBetterLayout(bestLayout, candidate, targetDotCount);
                if (candidate != null && candidate.dots.size() <= targetDotCount) {
                    sparseLayout = candidate;
                    break;
                }
            }
        }

        if (denseLayout != null && sparseLayout != null) {
            double lowPitch = denseLayout.pitch;
            double highPitch = sparseLayout.pitch;
            for (int i = 0; i < 18; i++) {
                double midPitch = (lowPitch + highPitch) / 2.0;
                candidate = generateBestOffsetHexLayout(shape, bounds, centerDot, midPitch,
                        targetDotCount);
                bestLayout = chooseBetterLayout(bestLayout, candidate, targetDotCount);
                if (candidate == null) {
                    break;
                }
                if (candidate.dots.size() >= targetDotCount) {
                    denseLayout = candidate;
                    lowPitch = midPitch;
                }
                else {
                    sparseLayout = candidate;
                    highPitch = midPitch;
                }
            }
        }

        if (bestLayout != null && bestLayout.dots.size() == targetDotCount) {
            return bestLayout;
        }
        if (denseLayout != null) {
            return denseLayout;
        }
        return bestLayout;
    }

    private HexLayout generateBestOffsetHexLayout(Shape shape, Rectangle2D bounds, LocalDot centerDot,
            double pitch, int targetDotCount) {
        if (!(pitch > 0.0)) {
            return null;
        }

        HexLayout bestLayout = null;
        int phaseSteps = 7;
        for (int offsetYIndex = 0; offsetYIndex < phaseSteps; offsetYIndex++) {
            double offsetY = (((double) offsetYIndex / (phaseSteps - 1)) - 0.5)
                    * (Math.sqrt(3.0) * pitch);
            for (int offsetXIndex = 0; offsetXIndex < phaseSteps; offsetXIndex++) {
                double offsetX = (((double) offsetXIndex / (phaseSteps - 1)) - 0.5) * pitch;
                List<LocalDot> dots = generateHexDots(shape, bounds, centerDot, pitch, offsetX,
                        offsetY);
                HexLayout candidate = new HexLayout(pitch, dots,
                        calculateCentroidDistanceSquared(dots, centerDot));
                bestLayout = chooseBetterLayout(bestLayout, candidate, targetDotCount);
            }
        }
        return bestLayout;
    }

    private List<LocalDot> generateHexDots(Shape shape, Rectangle2D bounds, LocalDot centerDot,
            double pitch, double offsetX, double offsetY) {
        double rowPitch = pitch * Math.sqrt(3.0) / 2.0;
        List<LocalDot> dots = new ArrayList<>();
        int rowStart = (int) Math.floor((bounds.getMinY() - centerDot.y - offsetY) / rowPitch) - 2;
        int rowEnd = (int) Math.ceil((bounds.getMaxY() - centerDot.y - offsetY) / rowPitch) + 2;
        for (int row = rowStart; row <= rowEnd; row++) {
            double y = centerDot.y + offsetY + (row * rowPitch);
            if (y < bounds.getMinY() - rowPitch || y > bounds.getMaxY() + rowPitch) {
                continue;
            }
            double rowOffsetX = ((row & 1) == 0) ? 0.0 : (pitch / 2.0);
            int columnStart = (int) Math
                    .floor((bounds.getMinX() - centerDot.x - offsetX - rowOffsetX) / pitch) - 2;
            int columnEnd = (int) Math
                    .ceil((bounds.getMaxX() - centerDot.x - offsetX - rowOffsetX) / pitch) + 2;
            for (int column = columnStart; column <= columnEnd; column++) {
                double x = centerDot.x + offsetX + rowOffsetX + (column * pitch);
                if (x < bounds.getMinX() - pitch || x > bounds.getMaxX() + pitch) {
                    continue;
                }
                if (shape.contains(x, y)) {
                    dots.add(new LocalDot(x, y));
                }
            }
        }
        return dots;
    }

    private HexLayout chooseBetterLayout(HexLayout currentBest, HexLayout candidate,
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
        if (candidate.centroidDistanceSquared < currentBest.centroidDistanceSquared) {
            return candidate;
        }
        if (candidate.centroidDistanceSquared > currentBest.centroidDistanceSquared) {
            return currentBest;
        }
        return candidate.dots.size() < currentBest.dots.size() ? candidate : currentBest;
    }

    private double calculateCentroidDistanceSquared(List<LocalDot> dots, LocalDot centerDot) {
        if (dots.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }

        double sumX = 0.0;
        double sumY = 0.0;
        for (LocalDot dot : dots) {
            sumX += dot.x;
            sumY += dot.y;
        }
        LocalDot centroid = new LocalDot(sumX / dots.size(), sumY / dots.size());
        return centroid.distanceSquared(centerDot);
    }

    private List<LocalDot> selectEvenlySpacedSubset(List<LocalDot> candidates, int targetDotCount,
            LocalDot centerDot) {
        if (candidates.size() <= targetDotCount) {
            return new ArrayList<>(candidates);
        }

        List<LocalDot> remaining = new ArrayList<>(candidates);
        List<LocalDot> selected = new ArrayList<>();
        LocalDot firstDot = findNearestDot(remaining, centerDot);
        selected.add(firstDot);
        remaining.remove(firstDot);

        while (selected.size() < targetDotCount && !remaining.isEmpty()) {
            LocalDot bestCandidate = null;
            double bestDistance = -1.0;
            for (LocalDot candidate : remaining) {
                double minDistance = Double.POSITIVE_INFINITY;
                for (LocalDot selectedDot : selected) {
                    minDistance = Math.min(minDistance, candidate.distanceSquared(selectedDot));
                }
                if (minDistance > bestDistance) {
                    bestDistance = minDistance;
                    bestCandidate = candidate;
                }
            }
            selected.add(bestCandidate);
            remaining.remove(bestCandidate);
        }

        return selected;
    }

    private List<LocalDot> orderDotsByNearestNeighbor(List<LocalDot> dots, LocalDot startDot) {
        if (dots.size() < 2) {
            return dots;
        }

        List<LocalDot> remaining = new ArrayList<>(dots);
        List<LocalDot> ordered = new ArrayList<>();
        LocalDot current = findNearestDot(remaining, startDot);
        ordered.add(current);
        remaining.remove(current);

        while (!remaining.isEmpty()) {
            LocalDot next = findNearestDot(remaining, current);
            ordered.add(next);
            remaining.remove(next);
            current = next;
        }
        return ordered;
    }

    private LocalDot findNearestDot(List<LocalDot> dots, LocalDot reference) {
        LocalDot nearest = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (LocalDot dot : dots) {
            double distance = dot.distanceSquared(reference);
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = dot;
            }
        }
        return nearest;
    }

    private LocalDot calculateCenterDot(Shape shape, Rectangle2D bounds) {
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
}
