package frc.team6502.robot.commands.general

import frc.team6502.robot.RobotContainer
import frc.team6502.robot.commands.balls.Shoot
import frc.team6502.robot.commands.drive.AutoDrive
import frc.team6502.robot.commands.drive.BallHunter
import kyberlib.auto.pathing.TravelingSalesman
import kyberlib.command.CommandManager
import kyberlib.math.units.Pose2d
import kyberlib.math.units.extensions.degrees
import kyberlib.math.units.extensions.inches
import kyberlib.simulation.field.KField2d

/**
 * Offers a high level strategy loop. Main Brain center of the robot.
 * It checks what part of the game loop the robot is in and then dispatches the next commands
 */
object Strategy {
    var collectedBalls = 0
    private val foundBalls
        get() = KField2d.goals.filter { it.name == "ball" }.size

    private var goalPose = Pose2d(151.532.inches, 79.inches, 0.degrees)  // was 171.532
    /**
     * When all the queued commands are done, it requests here what to do next
     */
    fun plan() {
        if (collectedBalls > 0) shoot()
        else if (foundBalls > 0) collectBalls()
        else searchForBalls()
        debug()
    }

    /**
     * Spot to debug imporatnt values
     */
    private fun debug() {}

    /**
     * Paths to the goal and then dispenses
     */
    private fun shoot() {
        println("Shooting")
        CommandManager.enqueue(AutoDrive(goalPose).andThen(Shoot()))
    }

    /**
     * Drives to each ball and picks it up
     */
    private fun collectBalls() {
        println("collecting route: $foundBalls")
        val goals = KField2d.goals.filter { it.name == "ball" }
        val points = goals.map { it.position }
        val route = TravelingSalesman(points.toMutableList(),
            RobotContainer.navigation.position, goalPose.translation
        ).bruteForce()
        println(route)
        for (waypoint in route.slice(IntRange(1, route.size-2))) {
            val goal = goals.find { it.position == waypoint }
            if (goal != null) {
                val command = goal.command
                CommandManager.enqueue(command)
            }
        }
    }

    /**
     * Searches for more balls to pick up
     */
    private fun searchForBalls() {
        println("searching")
        CommandManager.enqueue(BallHunter())
    }
}