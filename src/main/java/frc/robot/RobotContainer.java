// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package frc.robot;

import com.pathplanner.lib.commands.PathPlannerAuto;

import edu.wpi.first.wpilibj.Joystick;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;
import edu.wpi.first.wpilibj2.command.Command;
import frc.robot.Auto.AutoDrive;
import frc.robot.Auto.FieldPositioning;
import frc.robot.Auto.NotePositions;
import frc.robot.Auto.PathPlannerDrive;
import frc.robot.Auto.Position;
import frc.robot.Components.Carriage;
import frc.robot.Components.Elevator;
import frc.robot.Components.Shooter;
import frc.robot.Core.Time;
import frc.robot.Devices.BetterPS4;
import frc.robot.Devices.BinarySensor;
import frc.robot.Devices.Imu;
import frc.robot.Devices.LimeLight;
import frc.robot.Devices.Motor.TalonFX;
import frc.robot.Drive.PositionedDrive;
import frc.robot.Util.AngleMath;
import frc.robot.Util.Container;
import frc.robot.Util.DeSpam;
import frc.robot.Util.Lambda;
import frc.robot.Util.MathPlus;
import frc.robot.Util.PDConstant;
import frc.robot.Util.PIDController;
import frc.robot.Util.Promise;
import frc.robot.Util.ScaleInput;
import frc.robot.Util.Vector2;

public class RobotContainer {
  public final static boolean isDriveDisabled = false;
  final static boolean shotDuringAuton = false;

  // controllers
  BetterPS4 con = SubsystemInit.con();
  Joystick joystick = SubsystemInit.joystick();

  // drive
  PositionedDrive drive = SubsystemInit.drive();

  // positioning
  Imu imu = SubsystemInit.imu();
  LimeLight shooterLimeLight = SubsystemInit.shooterLimelight();
  LimeLight intakeLimeLight = SubsystemInit.intakeLimelight();
  FieldPositioning fieldPositioning = SubsystemInit.fieldPositioning(drive, imu, shooterLimeLight, new Vector2(0, 0));
  PathPlannerDrive autoDrive = SubsystemInit.autoDrive(imu);

  // subsystems
  Shooter shooter = SubsystemInit.shooter();
  Elevator elevator = SubsystemInit.elevator();
  TalonFX intake = SubsystemInit.intake();
  BinarySensor intakeSensor = SubsystemInit.intakeSensor();
  Carriage carriage = SubsystemInit.carriage(intakeSensor);

  public RobotContainer() {
    // initialize auto selector
    SmartDashboard.putStringArray("Auto List",
        new String[] { "left", "right", "no auto", "pathplanner", "commit arson" });
  }

  DeSpam dSpam = new DeSpam(0.5);

  static Vector2 speakerPosition() {
    Vector2 speakerPosition = new Vector2(SubsystemInit.isRed() ? 337.87 : -337.87, 60);
    return speakerPosition;
  }

  // teleop

  Lambda teleop() {
    PIDController turnPD = new PIDController(new PDConstant(0.6, 0.0));// p was .8 on 3/13

    final Container<Boolean> isAutoAimOn = new Container<>(true);

    return () -> {
      boolean isShooting = false; // this is updated in `drive` and later used
      { // drive
        var targetPos = speakerPosition();
        // .add(fieldPositioning.getFieldRelativeSpeed().multiply(0.5));

        final var displacementFromTar = targetPos
            .minus(fieldPositioning.getPosition());

        var correction = -turnPD.solve(AngleMath.getDelta(displacementFromTar.getTurnAngleDeg() - 90,
            fieldPositioning.getTurnAngle()));

        var pointingTar = shooter.isSpinning() && elevator.isDown() && isAutoAimOn.val;

        boolean canAutoShoot = MathPlus.withinBounds(displacementFromTar.getMagnitude(), 142.0, 136.0)
            && correction < 0.2;

        if (elevator.isDown() && shooter.isAtVelocity() && (canAutoShoot || con.getR1Button())) {
          isShooting = true;
        }

        // this is how we control our drive... its a lot
        if ((con.getLeftStick().getMagnitude() + Math.abs(con.getRightX()) > 0.1) || pointingTar)
          drive.power(
              // we get the magnitude of the left stick and apply a concave up curve to it
              // this becomes the magnitude of the translational voltage on each module
              ScaleInput.curve(con.getLeftStick().getMagnitude(), 1.5) * 11.99, // go voltage
              // We determine our robot's driver relative position
              // then we rotate our translation to be driver relative
              (con.getLeftStick().getAngleDeg()) - fieldPositioning.getTurnAngle()
                  + ((SubsystemInit.isRed()) ? 180 : 0), // go angle
              // by default, our turn voltage is just our right stick x
              // unless we are auto aiming
              (!pointingTar) ? con.getRightX() * -11.99
                  : correction, // turn voltage
              false); // we don't throw an error if we tell our robot to go faster than it can
        else
          drive.power(0, 0, 0);
      }

      { // intake and carriage

        if (isShooting) {
          carriage.shoot();
        } else if (con.getL2Button() && !carriage.hasNote() && elevator.isDown()) {
          // intake
          carriage.intake();
          intake.setVelocity(0.3 * 360);
        } else if (con.getCrossButton()) {
          // outtake
          carriage.outTake();
          intake.setVoltage(-12);
        } else if (!elevator.isDown() && con.getR1Button()) {
          // outtake but into amp
          carriage.outTake();
          intake.setVoltage(0);
        } else {
          // do nothing
          carriage.stop();
          intake.setVoltage(0);
        }

        if (elevator.isDown() && con.getR2ButtonPressed()) {
          shooter.toggleSpinning();
          if (shooter.isSpinning()) {
            carriage.prepShot();
          } else {
            carriage.unPrepShot();
          }
          // changes limelight state when shooting
          // if (shooter.isSpinning()) {
          // shooterLimeLight.setLEDState(3);
          // } else {
          // shooterLimeLight.setLEDState(1);
          // }
        }
      }

      { // arrow buttons on PS4
        if (con.povChanged()) {
          switch (con.getPOV()) {
            case 0: // Button Up
              break;
            case 90: // Button Right
              // Disable Auto Aim
              isAutoAimOn.val = !isAutoAimOn.val;
              break;
            case 180: // Button Down
              // Toggle auto fw
              break;
            case 270: // Button Left
              break;
          }
        }
      }

      { // elevator
        if (con.getL1ButtonPressed()) {
          if (elevator.isDown())
            elevator.moveUp();
          else
            elevator.moveDown();
        }

        if (joystick.getRawButton(6))
          elevator.stretch();

        if (joystick.getRawButton(3))
          elevator.climbDown();

        if (joystick.getRawButton(5)) {
          elevator.moveToClimb();
        }

        if (joystick.getPOV() != -1) {
          switch (joystick.getPOV()) {
            case 0: {
              elevator.moveRaw(0.2 * 360);
              break;
            }
            case 180:
              elevator.moveRaw(-0.2 * 360);
              break;

            default:
              break;
          }
        }
      }

      { // logs
        SmartDashboard.putNumber("Gyro", fieldPositioning.getTurnAngle());
        if (carriage.hasNote()) {
          SmartDashboard.putString("DB/String 0", "Its inside of me");
        } else {
          SmartDashboard.putString("DB/String 0", "Out Daddy");
        }
        if (shooter.isSpinning()) {
          SmartDashboard.putString("DB/String 1", "Shooter Is Spinning");
        } else {
          SmartDashboard.putString("DB/String 1", "Shooter Not Spinning");
        }

        // logs field position
        // dSpam.exec(() -> {
        // System.out.println(fieldPositioning.getPosition() + " " +
        // fieldPositioning.getTurnAngle());
        // });
      }
    };
  }

  public Command getTeleopCommand() {
    var periodic = new Container<Lambda>();
    return new Command() {
      @Override
      public void initialize() {
        periodic.val = teleop();
      }

      @Override
      public void execute() {
        periodic.val.run();
      }
    };
  }

  // auto

  Promise shoot(AutoDrive robor) {
    if (!shooter.isSpinning())
      shooter.toggleSpinning();

    return Promise.immediate().then(() -> {
      robor.setAngleTar(171);
      return robor.moveTo(new Vector2(217, 83));
    })
        .then(() -> Promise.timeout(2))
        .then(() -> carriage.shoot())
        .then(() -> Promise.timeout(6));
  }

  void startAuto(boolean startedLeft) {

    // dSpam.exec(() -> {
    // System.out
    // .println(
    // fieldPositioning.getPosition() + "correct " + robor.displacement + " "
    // + fieldPositioning.getTurnAngle());
    // });
    AutoDrive robor = new AutoDrive(fieldPositioning,
        new Position(180, fieldPositioning.getPosition().add(new Vector2(0, 0))),
        drive,
        new PDConstant(0.25, 0.02),
        new PDConstant(0.4, 0));

    if (!shooter.isSpinning())
      shooter.toggleSpinning();

    // !changed
    Promise.immediate()
        // .then(() -> robor.moveTo(new Vector2(240, 33)))
        .then(() -> shoot(robor))
        .then(() -> {
          carriage.intake();
          intake.setVelocity(0.3 * 360);
        });
    // .then(() -> robor.pointAndThenMoveTo(NotePositions.mid1));
  }

  public Command getAutonomousCommand(String autonToRun) {
    // determine if left or right of speaker
    boolean isLeft;
    switch (autonToRun) {
      case "left":
        isLeft = true;
        break;
      case "right":
        isLeft = false;
        break;
      case "pathplanner": {
        PathPlannerAuto auto = new PathPlannerAuto("New Auto");
        return auto;
      }
      // do nothing if auto is unrecognized or no auto is selected
      default:
      case "commit arson":
        return new Command() {
          public void initialize() {
            drive.setAlignmentThreshold(0.2);
            drive.power(0, 0, 12);
          }

          public void execute() {
            shooter.spin();
            Time.timeout(() -> {
              carriage.shoot();
            }, 3);
          }
        };
      case "no auto":
        return new Command() {
        };
    }

    return new Command() {
      @Override
      public void initialize() {
        drive.setAlignmentThreshold(0.2);
        // if we are on the left or right side of the speaker,
        // we want to turn the camera towards it
        if (isLeft)
          drive.power(2, 70, 0.5);
        else
          drive.power(2, 180 - 70, -0.5);
      }

      @Override
      public void execute() {
        if (fieldPositioning.hasGottenLimeLightFrame()) {
          startAuto(isLeft);
          cancel();
        }
      }

      @Override
      public void end(boolean interrupted) {
        if (shooter.isSpinning())
          shooter.toggleSpinning();
      }
    };
  }
}