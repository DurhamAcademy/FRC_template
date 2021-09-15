package frc.team6502.robot.auto

import edu.wpi.first.wpilibj.controller.RamseteController
import edu.wpi.first.wpilibj.estimator.DifferentialDrivePoseEstimator
import edu.wpi.first.wpilibj.estimator.MecanumDrivePoseEstimator
import edu.wpi.first.wpilibj.geometry.Pose2d
import edu.wpi.first.wpilibj.geometry.Rotation2d
import edu.wpi.first.wpilibj.geometry.Translation2d
import edu.wpi.first.wpilibj.smartdashboard.FieldObject2d
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard
import edu.wpi.first.wpilibj.trajectory.Trajectory
import edu.wpi.first.wpilibj.trajectory.TrajectoryConfig
import edu.wpi.first.wpilibj.trajectory.TrajectoryGenerator
import edu.wpi.first.wpilibj2.command.*
import edu.wpi.first.wpiutil.math.MatBuilder
import edu.wpi.first.wpiutil.math.numbers.*
import kyberlib.math.units.extensions.degrees
import frc.team6502.robot.Constants
import frc.team6502.robot.RobotContainer
import frc.team6502.robot.subsystems.Drivetrain
import kyberlib.math.units.string
import java.util.*


/**
 * A Subsystem to manage and update the Robots position
 * @param initialPose the pose of the robot when Navigation begins
 * @author TateStaples
 */
class Navigation(initialPose: Pose2d) : SubsystemBase() {
    val field = KField2d()
    val fieldTraj: FieldObject2d = field.getObject("traj")
    private val trajectoryQueue: Queue<Trajectory> = LinkedList() // should I make this into seperate class
    val currentGoal: Goal? = null

    /**
     * A probability calculator to guess where the robot is from odometer and vision updates
     */
    private val difEstimator = DifferentialDrivePoseEstimator(
        heading, initialPose,
        MatBuilder(N5.instance, N1.instance).fill(0.02, 0.02, 0.01, 0.02, 0.02),  // State measurement standard deviations. X, Y, theta, dist_l, dist_r. (dist is encoder distance I think)
        MatBuilder(N3.instance, N1.instance).fill(0.02, 0.02, 0.01),  // Local measurement standard deviations. Left encoder, right encoder, gyro.
        MatBuilder(N3.instance, N1.instance).fill(0.1, 0.1, 0.01) // Global measurement standard deviations. X, Y, and theta.
    )
    private val mecEstimator = MecanumDrivePoseEstimator(
        heading, initialPose, Drivetrain.mecKinematics,
        MatBuilder(N3.instance, N1.instance).fill(0.02, 0.02, 0.01), // State measurement standard deviations. X, Y, theta, dist_l, dist_r. (dist is encoder distance I think)
        MatBuilder(N1.instance, N1.instance).fill(0.02), // Local measurement standard deviations. gyro.
        MatBuilder(N3.instance, N1.instance).fill(0.1, 0.1, 0.01) // Global measurement standard deviations. X, Y, and theta.
    )


    /**
     * A object with restrictions on how the robot will move
     */
    private val pathingConfig =
        TrajectoryConfig(Constants.velocity.value, Constants.acceleration.value).apply {
            if (Constants.MECANUM) setKinematics(Drivetrain.mecKinematics) else setKinematics(Drivetrain.difKinematics)
        }

    /**
     * Generate a through a list of positions
     *
     * @param waypoints list of Translation2d that the robot should go through
     */
    fun trajectory(waypoints: List<Translation2d>): Trajectory {
        val finalDelta = waypoints.last().minus(waypoints[waypoints.size-2])
        val finalRotation = Rotation2d(finalDelta.x, finalDelta.y)
        return TrajectoryGenerator.generateTrajectory(
            pose,
            waypoints,
            Pose2d(waypoints.last(), finalRotation),
            pathingConfig
        )
    }

    /**
     * Generate a command to follow a designated trajectory
     *
     * @param trajectory path for the robot to follow
     */
    fun ramsete(trajectory: Trajectory): RamseteCommand {
        return RamseteCommand(
            trajectory,
            this::pose,
            RamseteController(Constants.RAMSETE_BETA, Constants.RAMSETE_ZETA),
            Drivetrain.feedforward,
            Drivetrain.difKinematics,
            Drivetrain::difWheelSpeeds,
            Drivetrain.leftPID,
            Drivetrain.rightPID,
            // RamseteCommand passes volts to the callback
            Drivetrain::driveVolts,
            Drivetrain
        )
    }

    fun mecCommand(trajectory: Trajectory): MecanumControllerCommand {
        return MecanumControllerCommand(trajectory, {pose}, Drivetrain.mecKinematics,
            Drivetrain.leftPID, Drivetrain.rightPID, Drivetrain.rotationPID,
            Constants.velocity.value,
            Drivetrain::drive,
            Drivetrain
        )
    }

    init {
        SmartDashboard.putData("Field", field)
    }

    /**
     * The periodic call of Navigation
     *
     * Used to update the odometry of the robot and log Debug values
     */
    override fun periodic() {
        update()
        field.robotPose = pose
        if (Constants.DEBUG) {
            SmartDashboard.putNumber("gyro", heading.degrees)
        }
    }

    // ----- public variables ----- //
    // location
    var heading  // what direction the robot is facing
        get() = RobotContainer.gyro.fusedHeading.degrees
        set(value) {RobotContainer.gyro.fusedHeading = value.degrees}
    var pose  // the location and direction of the robot
        get() = if (Constants.MECANUM) mecEstimator.estimatedPosition else difEstimator.estimatedPosition
        set(value) {
            if (Constants.MECANUM) mecEstimator.resetPosition(value, heading)
            else difEstimator.resetPosition(value, heading)
            this.field.robotPose = value
        }
    val position  // the estimated location of the robot
        get() = pose.translation
    var currentTrajectory: Trajectory? = null
        set(value) {
            if (value != null)
                fieldTraj.setTrajectory(value)
            field = value
        }
    var activeCommand: Command
        get() = CommandScheduler.getInstance().requiring(Drivetrain)
        set(value) {
            value.execute()
        }

    /**
     * Update position based on estimated motion
     */
    private fun update() {  // estimate motion
        val pose2d: Pose2d
        if (Constants.MECANUM) pose2d = mecEstimator.update(heading, Drivetrain.mecWheelSpeeds)
        else pose2d = difEstimator.update(heading, Drivetrain.difWheelSpeeds, Drivetrain.leftVel, Drivetrain.rightVel)

        if (Constants.DEBUG)
            SmartDashboard.putString("pose", pose2d.string)
    }

    /**
     * Update position based on a different position guess
     *
     * @param globalPosition the detected pose of the Robot
     * @param time the time of the detection
     */
    fun update(globalPosition: Pose2d, time: Double) {  // apply global position update
        SmartDashboard.putString("global pose", globalPosition.string)
        if (Constants.MECANUM) mecEstimator.addVisionMeasurement(globalPosition, time)
        else difEstimator.addVisionMeasurement(globalPosition, time)
    }
}