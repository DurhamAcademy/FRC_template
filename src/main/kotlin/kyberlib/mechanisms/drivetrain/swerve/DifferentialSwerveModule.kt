package kyberlib.mechanisms.drivetrain.swerve

import edu.wpi.first.wpilibj.controller.PIDController
import edu.wpi.first.wpilibj.controller.SimpleMotorFeedforward
import edu.wpi.first.wpilibj.geometry.Rotation2d
import edu.wpi.first.wpilibj.geometry.Translation2d
import kyberlib.motorcontrol.GearRatio
import kyberlib.motorcontrol.KMotorController
import kyberlib.math.units.extensions.Length
import kyberlib.math.units.extensions.LinearVelocity
import kyberlib.math.units.extensions.metersPerSecond
import kotlin.math.PI

class DifferentialSwerveModule(location: Translation2d, private val gearRatio: GearRatio, wheelRadius: Length,
                               private val topMotor: KMotorController, private val bottomMotor: KMotorController
                                        ) : SwerveModule(location) {

    private val rotationPID = PIDController(0.07, 0.00, 0.01)
    private val feedforward = SimpleMotorFeedforward(0.0, 0.0, 0.0)

    private fun differentialControl(it: KMotorController): Double {
        val goal = stateSetpoint
        val ff = feedforward.calculate(it.linearVelocity.metersPerSecond, it.linearAcceleration.metersPerSecond)
        val velCorrection = it.PID.calculate(goal.speedMetersPerSecond)
        val rotationError = rotation - goal.angle
        val rotCorrection = rotationPID.calculate(rotationError.radians, goal.angle.radians)
        return ff + velCorrection + rotCorrection
    }
    init {
        topMotor.customControl = { it: KMotorController -> differentialControl(it) }
        bottomMotor.customControl = { it: KMotorController -> differentialControl(it) }
    }

    private val wheelCircumference: Length
    init {
        wheelCircumference = wheelRadius * PI * 2.0
    }

    override var rotation: Rotation2d
        get() {
            val top = topMotor.position.normalized
            val bottom = bottomMotor.position.normalized
            return top.minus(bottom)
        }
        set(value) {
            stateSetpoint.angle = value
        }

    override var speed: LinearVelocity
        get() {
            val topSpeed = topMotor.linearVelocity  // gearing might make this weird
            val bottomSpeed = bottomMotor.linearVelocity
            return topSpeed + bottomSpeed
        }
        set(value) {
            stateSetpoint.speedMetersPerSecond = value.metersPerSecond
        }


    override fun debug() {

    }
}