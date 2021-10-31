package kyberlib.motorcontrol

import edu.wpi.first.wpilibj.Timer
import edu.wpi.first.wpilibj.controller.ArmFeedforward
import edu.wpi.first.wpilibj.controller.PIDController
import edu.wpi.first.wpilibj.controller.ProfiledPIDController
import edu.wpi.first.wpilibj.controller.SimpleMotorFeedforward
import edu.wpi.first.wpilibj.smartdashboard.SendableBuilder
import edu.wpi.first.wpilibj.trajectory.TrapezoidProfile
import edu.wpi.first.wpilibj2.command.TrapezoidProfileCommand
import edu.wpi.first.wpilibj2.command.TrapezoidProfileSubsystem
import kyberlib.math.Filters.Differentiator
import kyberlib.math.units.extensions.*

typealias GearRatio = Double
typealias BrakeMode = Boolean

/**
 * Types of encoders that may be used
 */
enum class EncoderType {
    NONE, NEO_HALL, QUADRATURE
}

/**
 * Defines types of motors that can be used
 */
enum class MotorType {
    BRUSHLESS, BRUSHED
}

enum class ControlMode {
    VELOCITY, POSITION, VOLTAGE, NULL
}

/**
 * Stores data about an encoder. [reversed] means the encoder reading goes + when the motor is applied - voltage.
 */
data class KEncoderConfig(val cpr: Int, val type: EncoderType, val reversed: Boolean = false)
/**
 * A more advanced motor control with feedback control.
 */
abstract class KMotorController : KBasicMotorController() {
    // ----- configs ----- //
    /**
     * The multiplier to attach to the raw velocity. *This is not the recommended way to do this.* Try using gearRatio instead
     */
    var velocityConversionFactor = 1.0
        set(value) {
            field = value
            writeMultipler(velocityConversionFactor, positionConversionFactor)
        }

    /**
     * The multiplier to attach to the raw position. *This is not the recommended way to do this.* Try using gearRatio instead
     */
    var positionConversionFactor = 1.0
        set(value) {
            field = value
            writeMultipler(velocityConversionFactor, positionConversionFactor)
        }

    /**
     * Defines the relationship between rotation and linear motion for the motor.
     */
    var radius: Length? = null

    /**
     * Adds post-encoder gearing to allow for post-geared speeds to be set.
     */
    var gearRatio: GearRatio = 1.0
        set(value) {
            field = value
            writeMultipler(gearRatio.rpm.rotationsPerSecond, gearRatio)
        }

    /**
     * Settings relevant to the motor controller's encoder.
     */
    var encoderConfig: KEncoderConfig = KEncoderConfig(0, EncoderType.NONE)
        set(value) {
            if (configureEncoder(value)) field = value
            else System.err.println("Invalid encoder configuration")
        }

    // ----- advanced tuning ----- //
    /**
     * Proportional gain of the customControl controller.
     */
    var kP: Double
        get() = PID.p
        set(value) {
            PID.p = value
            writePid(kP, kI, kD)
        }

    /**
     * Integral gain of the customControl controller.
     */
    var kI: Double
        get() = PID.i
        set(value) {
            PID.i = value
            writePid(kP, kI, kD)
        }

    /**
     * Derivative gain of the customControl controller.
     */
    var kD: Double
        get() = PID.d
        set(value) {
            PID.d = value
            writePid(kP, kI, kD)
        }

    /**
     * The max angular velocity the motor can have
     */
    var maxVelocity: AngularVelocity
        get() = constraints.maxVelocity.radiansPerSecond
        set(value) { constraints.maxVelocity = value.radiansPerSecond }
    /**
     * The max angular acceleation the motor can have
     */
    var maxAcceleration: AngularVelocity
        get() = constraints.maxAcceleration.radiansPerSecond
        set(value) { constraints.maxAcceleration = value.radiansPerSecond }
    /**
     * The max linear velocity the motor can have
     */
    var maxLinearVelocity: LinearVelocity
        get() = rotationToLinear(maxVelocity)
        set(value) { maxVelocity = linearToRotation(value) }
    /**
     * The max linear acceleration the motor can have
     */
    var maxLinearAcceleration: LinearVelocity
        get() = rotationToLinear(maxAcceleration)
        set(value) { maxAcceleration = linearToRotation(value) }

    private val constraints = TrapezoidProfile.Constraints(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
    var PID = ProfiledPIDController(0.0, 0.0, 0.0, constraints)  // what does the profile do?

    /**
     * Builtin control that will combine feedforward with the PID.
     * Useful in both position and velocity control
     */
    fun addFeedforward(feedforward: SimpleMotorFeedforward) {
        customControl = {
            var voltage = 0.0
            when (controlMode) {
                ControlMode.VELOCITY -> {
                    val ff = feedforward.calculate(linearVelocity.metersPerSecond, linearAcceleration.metersPerSecond)
                    val pid = PID.calculate(linearVelocityError.metersPerSecond)
                    voltage = ff + pid
                }
                ControlMode.POSITION -> {
                    val pid = PID.calculate(positionError.radians)
                    voltage = pid
                }
                ControlMode.VOLTAGE -> voltage = it.voltage
            }
            voltage
        }
    }

    /**
     * Used to create builtin functions for angular position sheet.
     * @exception WhyFuckingLinear you should not have linear controlled arm.
     */
    fun addFeedforward(feedforward: ArmFeedforward) {
        assert(!linearConfigured) {"you shouldn't be using armFF for linear motors"}
        customControl = {
            var voltage = 0.0
            if (controlMode == ControlMode.POSITION) {
                val ff = feedforward.calculate(position.radians, velocity.radiansPerSecond, acceleration.radiansPerSecond)
                val pid = PID.calculate(positionError.radians)
                voltage = ff + pid
            } else if (controlMode == ControlMode.VELOCITY) {
                val ff = feedforward.calculate(position.radians, velocity.radiansPerSecond, acceleration.radiansPerSecond)
                val pid = PID.calculate(velocityError.radiansPerSecond)
                voltage = ff + pid
            } else if (controlMode == ControlMode.VOLTAGE) voltage = it.voltage

            voltage

        }
    }

    /**
     * Follows a trapezoidal motion profile and then reverts when done
     */
    fun followProfile(profile: TrapezoidProfile) {
        val timer = Timer()
        val prevControl = customControl
        timer.start()
        customControl = {
            val state = profile.calculate(timer.get())
            position = state.position.radians
            if (profile.isFinished(timer.get())) {
                timer.stop()
                customControl = prevControl
            }
            if (prevControl != null) prevControl(it) else 0.0
        }
    }

    // todo: maybe separate custom control by mode
    /**
     * The control function of the robot.
     * Uses the motor state to determine what voltage should be applied.
     * Can be set to null to use in-built motor control system
     */
    var customControl: ((it: KMotorController) -> Double)? = {
        println(controlMode)
        voltage = when (controlMode) {
            ControlMode.POSITION -> PID.calculate(positionError.radians)
            ControlMode.VELOCITY -> PID.calculate(velocityError.radiansPerSecond)
            ControlMode.VOLTAGE -> it.voltage
            else -> 0.0
        }
        voltage
    }

    // ----- main getter/setter methods ----- //
    /**
     * Angle that the motor is at / should be at
     */
    var position: Angle = 0.degrees
        get() {
            controlMode = ControlMode.POSITION
            if (!real) return field
            assert(encoderConfigured)
            return (rawPosition.value / gearRatio).radians
        }
        set(value) {
            if (!real) field = value
            positionSetpoint = value
        }

    /**
     * Linear Position that the
     */
    var linearPosition: Length
        get() = rotationToLinear(position)
        set(value) { position = linearToRotation(value) }

    var velocity: AngularVelocity = 0.rpm
        get() {
            controlMode = ControlMode.VELOCITY
            if (!real)  {
                acceleration = accelerationCalculator.calculate(field.radiansPerSecond).radiansPerSecond
                return field
            }
            assert(encoderConfigured)
            val vel = rawVelocity / gearRatio
            acceleration = accelerationCalculator.calculate(vel.radiansPerSecond).radiansPerSecond
            return vel
        }
        set(value) {
            if (!real) field = value
            velocitySetpoint = value
        }

    var linearVelocity: LinearVelocity
        get() = rotationToLinear(velocity)
        set(value) { velocity = linearToRotation(value) }

    val positionError
        get() = position - positionSetpoint
    val linearPositionError
        get() = linearPosition - linearPositionSetpoint
    val velocityError
        get() = velocity - velocitySetpoint
    val linearVelocityError
        get() = linearVelocity - linearVelocitySetpoint

    private var accelerationCalculator = Differentiator()
    var acceleration = 0.rpm
    val linearAcceleration
        get() = rotationToLinear(acceleration)

    // ----- this is where you put the advanced controls ---- //
    /**
     * Sets the angle to which the motor should go
     */
    var positionSetpoint: Angle = 0.rotations
        private set(value) {
            field = value
            if(!closedLoopConfigured && real) rawPosition = value
        }

    /**
     * Sets the velocity to which the motor should go
     */
    var velocitySetpoint: AngularVelocity = 0.rpm
        private set(value) {
            field = value
            if (!closedLoopConfigured && real) rawVelocity = value
        }

    /**
     * Sets the linear position to which the motor should go
     */
    val linearPositionSetpoint: Length
        get() = rotationToLinear(positionSetpoint)

    /**
     * Sets the linear velocity to which the motor should go
     */
    val linearVelocitySetpoint: LinearVelocity
        get() = rotationToLinear(velocitySetpoint)

    // ----- util functions -----//
    /**
     * Converts linear units to rotational units
     * @exception LinearUnconfigured: you must set wheel radius before using
     */
    private fun linearToRotation(len: Length): Angle {
        if (!linearConfigured) throw LinearUnconfigured
        return len.toAngle(radius!!)
    }
    private fun linearToRotation(vel: LinearVelocity): AngularVelocity {
        if (!linearConfigured) throw LinearUnconfigured
        return vel.toAngularVelocity(radius!!)
    }

    /**
     * Converts rotational units to linear
     * @exception LinearUnconfigured you must set wheel radius before using
     */
    private fun rotationToLinear(ang: Angle): Length {
        if (!linearConfigured) throw LinearUnconfigured
        return ang.toCircumference(radius!!)
    }
    private fun rotationToLinear(vel: AngularVelocity): LinearVelocity {
        if (!linearConfigured) throw LinearUnconfigured
        return vel.toTangentialVelocity(radius!!)
    }

    /**
     * Updates the voltage from customControl and updates followers
     */
    override fun update() {
        super.update()
        if (customControl != null) voltage = customControl!!(this)
    }

    // ----- meta information ----- //
    /**
     * Does the motor controller have a rotational to linear motion conversion defined? (i.e. wheel radius)
     * Allows for linear units to be used.
     */
    private val linearConfigured
        get() = radius != null

    /**
     * Does the motor controller have an encoder configured?
     * Allows for closed-loop control methods to be used
     */
    protected val encoderConfigured
        get() = (encoderConfig.type != EncoderType.NONE && encoderConfig.cpr > 0)

    /**
     * Does the motor have closed-loop gains set?
     * Allows for closed-loop control methods to be used
     */
    private val closedLoopConfigured
        get() = encoderConfigured && customControl != null

    // ----- low level getters and setters (customized to each encoder type) ----- //
    /**
     * Resets motor to a certain positions
     * Does *not* move to angle, just changes the variable
     */
    abstract fun resetPosition(position: Angle = 0.rotations)
    fun resetPosition(position: Length) {
        resetPosition(linearToRotation(position))
    }

    /**
     * The native motors get and set methods for position.
     * Recommend using Position instead because it is safer.
     */
    abstract var rawPosition: Angle
        protected set

    /**
     * Native motor get and set for angular velocity.
     * Recommend using Velocity instead because it is safer
     */
    abstract var rawVelocity: AngularVelocity
        protected set

    /**
     * Max current draw for the motors
     */
    abstract var currentLimit: Int

    protected abstract fun writePid(p: Double, i: Double, d: Double)

    /**
     * Set the conversion multipliers of to native motor
     */
    protected abstract fun writeMultipler(mv: Double, mp: Double)

    /**
     * Configures the respective ESC encoder settings when a new encoder configuration is set
     */
    protected abstract fun configureEncoder(config: KEncoderConfig): Boolean

    override fun initSendable(builder: SendableBuilder) {
        super.initSendable(builder)
        if (linearConfigured) {
            builder.addDoubleProperty("Velocity (m/s)", { linearVelocity.metersPerSecond }, null)
            builder.addDoubleProperty("Position (m)", { linearPosition.meters }, null)
        } else {
            builder.addDoubleProperty("Velocity (rpm)", { velocity.rpm }, null)
            builder.addDoubleProperty("Position (rot)", { position.rotations }, null)
        }
    }

    override fun debugValues(): Map<String, Any?> {
        val map = super.debugValues().toMutableMap()
        map.putAll(mapOf(
            "Angular Position" to position,
            "Angular Velocity" to velocity
        ))
        if (linearConfigured)
            map.putAll(mapOf(
                "Linear Position" to linearPosition,
                "Linear Velocity" to linearVelocity
            ))
        return map.toMap()
    }

    companion object LinearUnconfigured : Exception("You must set the wheel radius before using linear values")
}