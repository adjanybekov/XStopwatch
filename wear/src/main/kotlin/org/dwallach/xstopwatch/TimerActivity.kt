package org.dwallach.xstopwatch

import android.app.Activity
import android.app.Dialog
import android.app.DialogFragment
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.AlarmClock
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.TimePicker

import java.lang.ref.WeakReference
import java.util.Observable
import java.util.Observer

import kotlinx.android.synthetic.main.activity_timer.*
import org.jetbrains.anko.*

class TimerActivity : Activity(), Observer {
    private var notificationHelper: NotificationHelper? = null
    private var buttonStateHandler: Handler? = null
    private var playButton: ImageButton? = null
    private var stopwatchText: StopwatchText? = null

    class MyHandler(looper: Looper, timerActivity: TimerActivity) : Handler(looper) {
        private val timerActivityRef = WeakReference(timerActivity)

        override fun handleMessage(inputMessage: Message) {
            Log.v(TAG, "button state message received")
            timerActivityRef.get()?.setPlayButtonIcon()
        }
    }


    // see http://developer.android.com/guide/topics/ui/controls/pickers.html

    /**
     * this uses the built-in TimePickerDialog to ask the user to specify the hours and minutes
     * for the count-down timer. Of course, it works fine on the emulator and on a Moto360, but
     * totally fails on the LG G Watch and G Watch R, apparently trying to show a full-blown
     * Material Design awesome thing that was never tuned to fit on a watch. Instead, see
     * the separate TimePickerFragment class, which might be ugly, but at least it works consistently.

     * TODO: move back to this code and kill TimePickerFragment once they fix the bug in Wear
     */
    class FailedTimePickerFragment : DialogFragment(), TimePickerDialog.OnTimeSetListener {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            // Use the current time as the default values for the picker
            val duration = TimerState.duration // in milliseconds
            val minute = (duration / 60000 % 60).toInt()
            val hour = (duration / 3600000).toInt()

            // Create a new instance of TimePickerDialog and return it
            return TimePickerDialog(activity, R.style.Theme_Wearable_Modal, this, hour, minute, true)
        }

        override fun onTimeSet(view: TimePicker, hour: Int, minute: Int) {
            // Do something with the time chosen by the user
            Log.v(TAG, "User selected time: %d:%02d".format(hour, minute))
            TimerState.setDuration(null, hour * 3600000L + minute * 60000L)
            PreferencesHelper.savePreferences(context)
            PreferencesHelper.broadcastPreferences(context, Constants.timerUpdateIntent)
        }
    }

    // call to this specified in the layout xml files
    fun showTimePickerDialog(v: View) =
        TimePickerFragment().show(fragmentManager, "timePicker")
//        FailedTimePickerFragment().show(fragmentManager, "timePicker")

    // call to this specified in the layout xml files
    fun launchStopwatch(view: View) = startActivity<StopwatchActivity>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.v(TAG, "onCreate")

        try {
            val pinfo = packageManager.getPackageInfo(packageName, 0)
            val versionNumber = pinfo.versionCode
            val versionName = pinfo.versionName

            Log.i(TAG, "Version: $versionName ($versionNumber)")

        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "couldn't read version", e)
        }

        intent.log(TAG) // dumps info from the intent into the log

        // there's a chance we were launched through a specific intent to set a timer for
        // a particular length; this is how we figure it out
        val paramLength = intent.getIntExtra(AlarmClock.EXTRA_LENGTH, 0)

        // this used to be set to true, but not in recent Android Wear versions. We used
        // to default to "false", meaning we'd put up the activity and wouldn't start it.
        // We now default to "true", meaning we'll still put up the activity, but we'll
        // also start the timer.
        val skipUI = intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true)

        // Google's docs say: This action requests a timer to be started for a specific length of time.
        // If no length is specified, the implementation should start an activity that is capable of
        // setting a timer (EXTRA_SKIP_UI is ignored in this case). If a length is specified, and
        // EXTRA_SKIP_UI is true, the implementation should remove this timer after it has been dismissed.
        // If an identical, unused timer exists matching both parameters, an implementation may re-use
        // it instead of creating a new one (in this case, the timer should not be removed after dismissal).
        // This action always starts the timer.
        // (http://developer.android.com/reference/android/provider/AlarmClock.html)

        // Here's what we're going to do. In this app, we want to support only one timer, rather than
        // the multiple timers suggested by the documentation above. Our solution, at least for now:
        // if an intent comes in while a timer is running, the old one is nuked and the new one is it.
        // We're going to assume that SKIP_UI is true and treat that a hint to start the timer. If it's
        // explicitly false, which doesn't seem likely, then we'll assume that Android is telling us
        // to have more user interaction, and therefore we won't auto-start the timer.

        if (paramLength > 0 && paramLength <= 86400) {
            Log.v(TAG, "onCreate, somebody told us a time value: $paramLength")
            val durationMillis = (paramLength * 1000).toLong()
            TimerState.setDuration(this@TimerActivity, durationMillis)
            TimerState.reset(this@TimerActivity)
            if (skipUI)
                TimerState.click(this@TimerActivity)

            PreferencesHelper.savePreferences(this@TimerActivity)
            PreferencesHelper.broadcastPreferences(this@TimerActivity, Constants.timerUpdateIntent)
        } else {
            // bring in saved preferences
            PreferencesHelper.loadPreferences(this@TimerActivity)
        }


        setContentView(R.layout.activity_timer)

        // This buttonState business is all about dealing with alarms, which go to
        // NotificationService, on a different thread, which needs to ping us to
        // update the UI, if we exist. This handler will always run on the UI thread.
        // It's invoked from the update() method down below, which may run on other threads.
        buttonStateHandler = MyHandler(Looper.getMainLooper(), this)

        watch_view_stub.setOnLayoutInflatedListener {
            Log.v(TAG, "onLayoutInflated")

            val resetButton = it.find<ImageButton>(R.id.resetButton)
            playButton = it.find<ImageButton>(R.id.playButton)
            stopwatchText = it.find<StopwatchText>(R.id.elapsedTime)

            stopwatchText?.setSharedState(TimerState)

            // now that we've loaded the state, we know whether we're playing or paused
            setPlayButtonIcon()

            // get the notification service running as well; it will stick around to make sure
            // the broadcast receiver is alive
            NotificationService.kickStart(this@TimerActivity)

            // set up notification helper, and use this as a proxy for whether
            // or not we need to set up everybody who pays attention to the timerState
            if (notificationHelper == null) {
                notificationHelper = NotificationHelper(this@TimerActivity,
                        R.drawable.sandwatch_trans,
                        resources.getString(R.string.timer_app_name),
                        TimerState)
                setStopwatchObservers(true)
            }

            resetButton.setOnClickListener {
                TimerState.reset(this@TimerActivity)
                PreferencesHelper.savePreferences(this@TimerActivity)
                PreferencesHelper.broadcastPreferences(this@TimerActivity, Constants.timerUpdateIntent)
            }

            playButton?.setOnClickListener {
                TimerState.click(this@TimerActivity)
                PreferencesHelper.savePreferences(this@TimerActivity)
                PreferencesHelper.broadcastPreferences(this@TimerActivity, Constants.timerUpdateIntent)
            }
        }
    }

    /**
     * install the observers that care about the timerState: "this", which updates the
     * visible UI parts of the activity, and the notificationHelper, which deals with the popup
     * notifications elsewhere

     * @param includeActivity If the current activity isn't visible, then make this false and it won't be notified
     */
    private fun setStopwatchObservers(includeActivity: Boolean) {
        TimerState.deleteObservers()
        if (notificationHelper != null)
            TimerState.addObserver(notificationHelper)
        if (includeActivity) {
            TimerState.addObserver(this)

            if (stopwatchText != null)
                TimerState.addObserver(stopwatchText)
        }
    }

    override fun onStart() {
        super.onStart()

        Log.v(TAG, "onStart")

        TimerState.isVisible = true
        setStopwatchObservers(true)
    }

    override fun onResume() {
        super.onResume()

        Log.v(TAG, "onResume")

        TimerState.isVisible = true
        setStopwatchObservers(true)
    }

    override fun onPause() {
        super.onPause()

        Log.v(TAG, "onPause")

        TimerState.isVisible = false
        setStopwatchObservers(false)
    }

    override fun update(observable: Observable?, data: Any?) {
        Log.v(TAG, "activity update")

        // We might be called on the UI thread or on a service thread; we need to dispatch this
        // entirely on the UI thread, since we're ultimately going to be monkeying with the UI.
        // Thus this nonsense.
        buttonStateHandler?.sendEmptyMessage(0)
    }

    private fun setPlayButtonIcon() {
        Log.v(TAG, "setPlayButtonIcon")
        playButton?.setImageResource(
                if(TimerState.isRunning)
                    android.R.drawable.ic_media_pause
                else
                    android.R.drawable.ic_media_play)
    }

    companion object {
        private const val TAG = "TimerActivity"
    }
}
