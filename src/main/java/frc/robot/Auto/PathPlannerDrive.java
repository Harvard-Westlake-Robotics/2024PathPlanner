package frc.robot.Auto.Drive;

import com.ctre.phoenix6.mechanisms.swerve.SwerveModule;

import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.math.kinematics.SwerveDriveKinematics;
import edu.wpi.first.math.kinematics.SwerveModulePosition;
import edu.wpi.first.wpilibj2.command.SubsystemBase;
import frc.robot.RobotContainer;
import frc.robot.Drive.SwerveModulePD;
import frc.robot.Util.AngleMath;
import frc.robot.Util.DeSpam;
import frc.robot.Util.PDConstant;
import frc.robot.Util.Vector2;

public class PathPlannerDrive extends SubsystemBase {

    /**
     * Drive is a class representing the swerve drive system of a robot.
     * It manages the coordination of the swerve modules for driving and turning
     * movements.
     */

    // Swerve modules for each corner of the robot.
    public SwerveModule frontLeft;
    protected SwerveModule frontRight;
    protected SwerveModule backLeft;
    protected SwerveModule backRight;

    // Dimensions of the robot.
    protected double widthInches;
    protected double lengthInches;
    protected double circumferenceInches; // Calculated circumference for turning calculations.

    // The minimum alignment before driving starts.
    private double alignmentThreshold = 1;

    /**
     * Sets the threshold for how closely aligned the modules need to be to their
     * target positions before driving begins.
     * 
     * @param newThreshold A value between 0 (exclusive) and 1 (inclusive)
     *                     representing the alignment threshold.
     */
    public void setAlignmentThreshold(double newThreshold) {
        if (newThreshold <= 0 || newThreshold > 1)
            throw new Error("Threshold must be in range (0, 1]");
        this.alignmentThreshold = newThreshold;
    }

    SwerveModule[] modules;
    SwerveDriveKinematics kinematics;

    /**
     * Constructor for Drive that sets up the swerve modules and the robot's
     * dimensions.
     * 
     * @param frontLeft    The front-left swerve module.
     * @param frontRight   The front-right swerve module.
     * @param backLeft     The back-left swerve module.
     * @param backRight    The back-right swerve module.
     * @param widthInches  The width of the robot in inches.
     * @param lengthInches The length of the robot in inches.
     */
    public PathPlannerDrive(SwerveModule frontLeft, SwerveModule frontRight, SwerveModule backLeft,
            SwerveModule backRight, double widthInches, double lengthInches) {
        this.frontLeft = frontLeft;
        this.frontRight = frontRight;
        this.backLeft = backLeft;
        this.backRight = backRight;
        this.widthInches = widthInches;
        this.lengthInches = lengthInches;
        this.circumferenceInches = 2 * Math.PI
                * Math.sqrt((widthInches * widthInches + lengthInches * lengthInches) / 2);
        modules = new SwerveModule[] {
                frontLeft, frontRight, backLeft, backRight
        };
        this.kinematics = new SwerveDriveKinematics(
                new Translation2d[] { new Translation2d(-0.3556, 0.3556), new Translation2d(0.3556, 0.3556),
                        new Translation2d(-0.3556, -0.3556), new Translation2d(0.3556, -0.3556) });

    }

    public SwerveModulePosition[] getPositions() {
        SwerveModulePosition[] positions = new SwerveModulePosition[modules.length];
        for (int i = 0; i < modules.length; i++) {
            positions[i] = modules[i].getPosition(true);
        }
        return positions;
    }

    public void fromChassisSpeeds(ChassisSpeeds speeds) {
        SwerveModuleStates[] states = kinematics.toChassisSpeedChassisSpeeds(speeds);
        for(int i = 0; i < modules.length; i++){
            modules[i].
        }
    }

    /**
     * Powers the robot's swerve modules to drive and turn according to specified
     * voltages and directions.
     * 
     * @param goSpeed             Directional speed in in/sec.
     * @param goDirectionDeg      Angle to translate towards in degrees
     * @param turnVelocity        deg/sec
     * @param errorOnLargeVoltage If true, throws an error when voltage exceeds 12V.
     */
    public void power(double goSpeed, double goDirectionDeg, double turnVelocity, boolean errorOnLargeVoltage) {
        if (RobotContainer.isDriveDisabled)
            stopGoPower();

        // Validation check for voltage limits.
        if (errorOnLargeVoltage) {
            if (Math.abs(goSpeed) > 12)
                throw new Error("Illegally large voltage - goVoltage");
            if (Math.abs(turnVelocity) > 12)
                throw new Error("Illegally large voltage - turnVoltage");
        }

        // converts angular velocity of the robot into a per module linear velocity
        // (in/sec)
        turnVelocity = turnVelocity / 180.0 * Math.PI
                * Math.sqrt(Math.pow(widthInches / 2, 2) + Math.pow(lengthInches / 2, 2));

        // Normalize the go direction angle.
        goDirectionDeg = AngleMath.conformAngle(goDirectionDeg);

        // Initialize targets for modules if not already set.
        if (moduleTargets == null)
            moduleTargets = new Vector2[4];

        // Calculate target vectors for each module based on driving and turning
        // directions.
        for (int quadrant = 1; quadrant <= 4; quadrant++) {
            var turnVec = getTurnVec(quadrant).multiply(turnVelocity);
            var goVec = Vector2.fromAngleAndMag(goDirectionDeg, goSpeed);
            var vec = goVec.add(turnVec);

            moduleTargets[quadrant - 1] = vec;
        }

        // Normalize voltages so that no module exceeds 12V.
        double largestVoltage = 0;
        for (Vector2 tar : moduleTargets) {
            if (Math.abs(tar.getMagnitude()) > largestVoltage)
                largestVoltage = Math.abs(tar.getMagnitude());
        }
        if (largestVoltage > 12) {
            for (int module = 0; module < 4; module++) {
                final double fac = 12.0 / largestVoltage;
                final var tar = moduleTargets[module];
                moduleTargets[module] = tar.withMagnitude(tar.getMagnitude() * fac);
            }
        }
    }

    // Overloaded method for power without the errorOnLargeVoltage flag.
    public void power(double goVoltage, double goDirectionDeg, double turnVoltage) {
        this.power(goVoltage, goDirectionDeg, turnVoltage, true);
    }

    // Sets the same PD constants for all swerve modules.
    public void setConstants(PDConstant constant) {
        frontLeft.setConstants(constant);
        frontRight.setConstants(constant);
        backLeft.setConstants(constant);
        backRight.setConstants(constant);
    }

    /**
     * Gets the vector representing the turning direction for a wheel in a given
     * quadrant.
     * Quadrants are numbered as follows:
     * 2 ↗ ↘ 1
     * 3 ↖ ↙ 4
     * 
     * @param quadrant The quadrant number.
     * @return The turning vector for the specified quadrant.
     */
    protected static Vector2 getTurnVec(int quadrant) {
        var squareSide = 1.0 / Math.sqrt(2);
        return new Vector2(
                (quadrant == 1 || quadrant == 2) ? -squareSide : squareSide,
                (quadrant == 2 || quadrant == 3) ? -squareSide : squareSide);
    }

    // Resets the drive system, typically called when initializing.
    public void reset() {
        // Reset logic can be implemented here if needed.
    }

    // Stops all drive power by setting the go voltage of all modules to zero.
    public void stopGoPower() {
        moduleTargets = null;
        for (SwerveModulePD module : new SwerveModulePD[] { frontRight, frontLeft, backLeft, backRight }) {
            module.setGoVoltage(0);
        }
    }

    DeSpam dSpam = new DeSpam(0.5);

    // Updates the swerve modules each tick based on the targets set by the power
    // method.
    protected void tick(double dTime) {
        if (RobotContainer.isDriveDisabled)
            stopGoPower();

        double error = 0;
        double total = 0;
        if (moduleTargets != null) {
            // System.out.println("voltage " + moduleTargets[0].getMagnitude());
            int quadrant = 1;
            for (SwerveModulePD module : new SwerveModulePD[] { frontRight, frontLeft, backLeft, backRight }) {
                final var tar = moduleTargets[quadrant - 1];
                error += tar.getMagnitude()
                        * (Math.abs(AngleMath.getDeltaReversable(module.getAngle(), tar.getAngleDeg()))
                                / 90.0);
                total += tar.getMagnitude();
                quadrant++;
            }
        }

        int quadrant = 1;
        for (SwerveModulePD module : new SwerveModulePD[] { frontRight, frontLeft, backLeft, backRight }) {
            if (moduleTargets != null) {
                var vec = moduleTargets[quadrant - 1];
                if (error / total < 1 - alignmentThreshold) {
                    module.setGoVoltage(vec.getMagnitude());
                } else {
                    module.setGoVoltage(0);
                }
                module.setTurnTarget(vec.getTurnAngleDeg());
            } else {
                module.setVelocity(0);
            }
            quadrant++;
        }
        frontLeft.tick(dTime);
        frontRight.tick(dTime);
        backLeft.tick(dTime);
        backRight.tick(dTime);
    }

    public void cleanUp() {
    }
}