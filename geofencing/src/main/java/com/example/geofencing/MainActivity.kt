@file:Suppress("DEPRECATION")

package com.example.geofencing

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.databinding.DataBindingUtil
import androidx.databinding.DataBindingUtil.setContentView
import androidx.lifecycle.SavedStateViewModelFactory
import androidx.lifecycle.ViewModelProvider
import androidx.viewbinding.BuildConfig
import com.google.android.gms.location.GeofencingClient
import com.example.geofencing.databinding.ActivityHuntMainBinding
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.material.snackbar.Snackbar

class MainActivity: AppCompatActivity(){

        private lateinit var binding: ActivityHuntMainBinding
        private lateinit var geofencingClient: GeofencingClient
        private lateinit var viewModel: GeofenceViewModel


        private val runningQOrLater = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q



        override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                binding = DataBindingUtil.setContentView(this, R.layout.activity_hunt_main)
                viewModel = ViewModelProvider(this)[GeofenceViewModel::class.java]
                binding.viewmodel = viewModel
                binding.lifecycleOwner = this

                // TODO: Step 9 instantiate the geofencing client
                // Create channel for notifications
                createChannel(this )
        }
       public override fun onStart() {
                super.onStart()
                checkPermissionsAndStartGeofencing()
       }


        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
                super.onActivityResult(requestCode, resultCode, data)
                if (requestCode == REQUEST_TURN_DEVICE_LOCATION_ON) {
                        checkDeviceLocationSettingsAndStartGeofence(false)
                }
        }
        override fun onNewIntent(intent: Intent?) {
                super.onNewIntent(intent)
                val extras = intent?.extras
                if(extras != null){
                        if(extras.containsKey(GeofencingConstants.EXTRA_GEOFENCE_INDEX)){
                                viewModel.updateHint(extras.getInt(GeofencingConstants.EXTRA_GEOFENCE_INDEX))
                                checkPermissionsAndStartGeofencing()
                        }
                }
        }

        /*
         * In all cases, we need to have the location permission.  On Android 10+ (Q) we need to have
         * the background permission as well.
         */
        override fun onRequestPermissionsResult(
                requestCode: Int,
                permissions: Array<String>,
                grantResults: IntArray
        ) {
                super.onRequestPermissionsResult(requestCode, permissions, grantResults)
                Log.d(TAG, "onRequestPermissionResult")

                if (
                        grantResults.isEmpty() ||
                        grantResults[LOCATION_PERMISSION_INDEX] == PackageManager.PERMISSION_DENIED ||
                        (requestCode == REQUEST_FOREGROUND_AND_BACKGROUND_PERMISSION_RESULT_CODE &&
                                grantResults[BACKGROUND_LOCATION_PERMISSION_INDEX] ==
                                PackageManager.PERMISSION_DENIED))
                {
                        Snackbar.make(
                                binding.activityMapsMain,
                                R.string.permission_denied_explanation,
                                Snackbar.LENGTH_INDEFINITE
                        )
                                .setAction(R.string.settings) {
                                        startActivity(Intent().apply {
                                                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                                data = Uri.fromParts("package", BuildConfig.LIBRARY_PACKAGE_NAME, null)
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        })
                                }.show()
                } else {
                        checkDeviceLocationSettingsAndStartGeofence()
                }
        }

        /**
         * This will also destroy any saved state in the associated ViewModel, so we remove the
         * geofences here.
         */
        override fun onDestroy() {
                super.onDestroy()
                removeGeofences()
        }

        /**
         * Starts the permission check and Geofence process only if the Geofence associated with the
         * current hint isn't yet active.
         */
        private fun checkPermissionsAndStartGeofencing() {
                if (viewModel.geofenceIsActive()) return
                if (foregroundAndBackgroundLocationPermissionApproved()) {
                        checkDeviceLocationSettingsAndStartGeofence()
                } else {
                        requestForegroundAndBackgroundLocationPermissions()
                }
        }

        /*
         *  Uses the Location Client to check the current state of location settings, and gives the user
         *  the opportunity to turn on location services within our app.
         */
        private fun checkDeviceLocationSettingsAndStartGeofence(resolve:Boolean = true) {
                val locationRequest = LocationRequest.create().apply {
                        priority = LocationRequest.PRIORITY_LOW_POWER
                }
                val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
                val settingsClient = LocationServices.getSettingsClient(this)
                val locationSettingsResponseTask =
                        settingsClient.checkLocationSettings(builder.build())
                locationSettingsResponseTask.addOnFailureListener { exception ->
                        if (exception is ResolvableApiException && resolve){
                                try {
                                        exception.startResolutionForResult(this@MainActivity,
                                                REQUEST_TURN_DEVICE_LOCATION_ON)
                                } catch (sendEx: IntentSender.SendIntentException) {
                                        Log.d(TAG, "Error getting location settings resolution: " + sendEx.message)
                                }
                        } else {
                                Snackbar.make(
                                        binding.activityMapsMain,
                                        R.string.location_required_error, Snackbar.LENGTH_INDEFINITE
                                ).setAction(android.R.string.ok) {
                                        checkDeviceLocationSettingsAndStartGeofence()
                                }.show()
                        }
                }
                locationSettingsResponseTask.addOnCompleteListener {
                        if ( it.isSuccessful ) {
                                addGeofenceForClue()
                        }
                }
        }

        /*
         *  Determines whether the app has the appropriate permissions across Android 10+ and all other
         *  Android versions.
         */
        @TargetApi(29)
        private fun foregroundAndBackgroundLocationPermissionApproved(): Boolean {
                val foregroundLocationApproved = (
                        PackageManager.PERMISSION_GRANTED ==
                                ActivityCompat.checkSelfPermission(this,
                                        Manifest.permission.ACCESS_FINE_LOCATION))
                val backgroundPermissionApproved =
                        if (runningQOrLater) {
                                PackageManager.PERMISSION_GRANTED ==
                                        ActivityCompat.checkSelfPermission(
                                                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                                        )
                        } else {
                                true
                        }
                return foregroundLocationApproved && backgroundPermissionApproved
        }

        /*
         *  Requests ACCESS_FINE_LOCATION and (on Android 10+ (Q) ACCESS_BACKGROUND_LOCATION.
         */
        @TargetApi(29 )
        private fun requestForegroundAndBackgroundLocationPermissions() {
                if (foregroundAndBackgroundLocationPermissionApproved())
                        return
                var permissionsArray = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                val resultCode = when {
                        runningQOrLater -> {
                                permissionsArray += Manifest.permission.ACCESS_BACKGROUND_LOCATION
                                REQUEST_FOREGROUND_AND_BACKGROUND_PERMISSION_RESULT_CODE
                        }
                        else -> REQUEST_FOREGROUND_ONLY_PERMISSIONS_REQUEST_CODE
                }
                Log.d(TAG, "Request foreground only location permission")
                ActivityCompat.requestPermissions(
                        this@MainActivity,
                        permissionsArray,
                        resultCode
                )
        }

        /*
         * Adds a Geofence for the current clue if needed, and removes any existing Geofence. This
         * method should be called after the user has granted the location permission.  If there are
         * no more geofences, we remove the geofence and let the viewmodel know that the ending hint
         * is now "active."
         */
        private fun addGeofenceForClue() {
                // TODO: Step 10 add in code to add the geofence
        }

        /**
         * Removes geofences. This method should be called after the user has granted the location
         * permission.
         */
        private fun removeGeofences() {
                // TODO: Step 12 add in code to remove the geofences
        }
        companion object {
                internal const val ACTION_GEOFENCE_EVENT =
                        "HuntMainActivity.treasureHunt.action.ACTION_GEOFENCE_EVENT"
        }
}

private const val REQUEST_FOREGROUND_AND_BACKGROUND_PERMISSION_RESULT_CODE = 33
private const val REQUEST_FOREGROUND_ONLY_PERMISSIONS_REQUEST_CODE = 34
private const val REQUEST_TURN_DEVICE_LOCATION_ON = 29
private const val TAG = "HuntMainActivity"
private const val LOCATION_PERMISSION_INDEX = 0
private const val BACKGROUND_LOCATION_PERMISSION_INDEX = 1