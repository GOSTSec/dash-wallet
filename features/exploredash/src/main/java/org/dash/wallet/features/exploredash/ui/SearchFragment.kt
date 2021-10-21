/*
 * Copyright 2021 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dash.wallet.features.exploredash.ui

import android.Manifest
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.maps.android.collections.CircleManager
import com.google.maps.android.collections.MarkerManager
import com.google.maps.android.ktx.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.DialogBuilder
import org.dash.wallet.common.ui.ListDividerDecorator
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.model.Merchant
import org.dash.wallet.features.exploredash.data.model.SearchResult
import org.dash.wallet.features.exploredash.databinding.FragmentSearchBinding
import org.dash.wallet.features.exploredash.ui.adapters.MerchantsAtmsResultAdapter
import org.dash.wallet.features.exploredash.ui.dialogs.TerritoryFilterDialog


@FlowPreview
@ExperimentalCoroutinesApi
@AndroidEntryPoint
class SearchFragment : Fragment(R.layout.fragment_search) {
    private val binding by viewBinding(FragmentSearchBinding::bind)
    private val viewModel: ExploreViewModel by activityViewModels()
    private var googleMap: GoogleMap? = null
    private lateinit var adapter: MerchantsAtmsResultAdapter
    private lateinit var mCurrentUserLocation : LatLng
    private var currentLocationMarker: Marker? = null
    private var currentLocationCircle: Circle? = null
    private val CURRENT_POSITION_MARKER_TAG = 0

    private var markerManager: MarkerManager? = null
    private var markerCollection: MarkerManager.Collection? = null
    private var circleManager: CircleManager? = null
    private var circleCollection: CircleManager.Collection? = null

    private val permissionRequestLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { isPermissionGranted ->
        if (isPermissionGranted){
            viewModel.monitorUserLocation()
            viewModel.observeCurrentUserLocation.observe(viewLifecycleOwner) {
                showLocationOnMap(it)
            }
        } else {
            showPermissionDeniedDialog()
        }
    }

    private fun showPermissionDeniedDialog() {
        val deniedPermissionDialog = DialogBuilder.warn(
            requireActivity(),
            R.string.permission_required_title,
            R.string.permission_required_message
        )
        deniedPermissionDialog.setPositiveButton(R.string.goto_settings) { _, _ ->
            val intent = createAppSettingsIntent()
            startActivity(intent)
        }
        deniedPermissionDialog.setNegativeButton(R.string.button_dismiss) { dialog: DialogInterface, _ ->
            dialog.dismiss()
        }
        deniedPermissionDialog.show()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbarTitle.text = getString(R.string.explore_where_to_spend)
        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.searchTitle.text = "United States" // TODO: use location to resolve

        val bottomSheet = BottomSheetBehavior.from(binding.contentPanel)
        bottomSheet.state = BottomSheetBehavior.STATE_HALF_EXPANDED

        binding.allOption.setOnClickListener {
            viewModel.setFilterMode(ExploreViewModel.FilterMode.All)
        }

        binding.physicalOption.setOnClickListener {
            viewModel.setFilterMode(ExploreViewModel.FilterMode.Physical)
        }

        binding.onlineOption.setOnClickListener {
            viewModel.setFilterMode(ExploreViewModel.FilterMode.Online)
        }

        binding.search.doOnTextChanged { text, _, _, _ ->
            binding.clearBtn.isVisible = !text.isNullOrEmpty()
            viewModel.submitSearchQuery(text.toString())
        }

        binding.search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val inputManager = requireContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                inputManager.toggleSoftInput(0, 0)
            }

            true
        }

        binding.clearBtn.setOnClickListener {
            binding.search.text.clear()
        }

        requireActivity().window?.decorView?.let { decor ->
            ViewCompat.setOnApplyWindowInsetsListener(decor) { _, insets ->
                val showingKeyboard = insets.isVisible(WindowInsetsCompat.Type.ime())

                if(showingKeyboard) {
                    bottomSheet.state = BottomSheetBehavior.STATE_EXPANDED
                }

                insets
            }
        }

        binding.filterBtn.setOnClickListener {
            lifecycleScope.launch {
                val territories = viewModel.getTerritoriesWithMerchants()
                TerritoryFilterDialog(territories, viewModel.pickedTerritory) { name, dialog ->
                    lifecycleScope.launch {
                        delay(300)
                        dialog.dismiss()
                        viewModel.pickedTerritory = name
                    }
                }.show(parentFragmentManager, "territory_filter")
            }
        }

        adapter = MerchantsAtmsResultAdapter { item, _ ->
            if (item is Merchant) {
                viewModel.openMerchantDetails(item)
            }
        }

        val divider = ContextCompat.getDrawable(requireContext(), R.drawable.list_divider)!!
        val decorator = ListDividerDecorator(divider, false, R.layout.group_header)
        binding.searchResultsList.addItemDecoration(decorator)
        binding.searchResultsList.adapter = adapter

        viewModel.searchResults.observe(viewLifecycleOwner) { results ->
            binding.noResultsText.isVisible = results.isEmpty()
            adapter.submitList(results)
            renderMerchantsOnMap(results)
            handleClickOnMerchantMarker(results)
        }

        viewModel.filterMode.observe(viewLifecycleOwner) {
            binding.allOption.isChecked = it == ExploreViewModel.FilterMode.All
            binding.allOption.isEnabled = it != ExploreViewModel.FilterMode.All
            binding.physicalOption.isChecked = it == ExploreViewModel.FilterMode.Physical
            binding.physicalOption.isEnabled = it != ExploreViewModel.FilterMode.Physical
            binding.onlineOption.isChecked = it == ExploreViewModel.FilterMode.Online
            binding.onlineOption.isEnabled = it != ExploreViewModel.FilterMode.Online

            if (it == ExploreViewModel.FilterMode.Online) {
                bottomSheet.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }

        viewModel.init()

        val mapFragment = childFragmentManager.findFragmentById(R.id.explore_map) as SupportMapFragment
        lifecycleScope.launchWhenStarted {
            googleMap = mapFragment.awaitMap()
            checkPermissionOrShowMap()
            setUserLocationMarkerMovementState()
        }
    }

    private fun setUserLocationMarkerMovementState() {
        markerCollection?.setOnMarkerDragListener(object : GoogleMap.OnMarkerDragListener {
            override fun onMarkerDragStart(p0: Marker) {
            }

            override fun onMarkerDrag(p0: Marker) {
            }

            override fun onMarkerDragEnd(marker: Marker) {
                currentLocationMarker?.position = marker.position
                currentLocationCircle?.center = marker.position
                googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(marker.position, calculateZoomLevel().toFloat()))
            }
        })

    }

    private fun renderMerchantsOnMap(results: List<SearchResult>) {
        markerCollection?.markers?.forEach { if (it.tag != CURRENT_POSITION_MARKER_TAG) it.remove() }
        results.forEach {
            if (it is Merchant) {
                markerCollection?.addMarker(MarkerOptions().apply {
                    position(LatLng(it.latitude!!, it.longitude!!))
                    anchor(0.5f, 0.5f)
                    val bitmap = getBitmapFromDrawable(R.drawable.merchant_marker)
                    icon(BitmapDescriptorFactory.fromBitmap(bitmap))
                    draggable(false)
                })
            }
        }
    }

    private fun handleClickOnMerchantMarker(results: List<SearchResult>) {
        markerCollection?.setOnMarkerClickListener { marker ->
            if (marker.tag == CURRENT_POSITION_MARKER_TAG) {
                false
            } else {
                val atmItemCoordinates = marker.position
                results.forEach {
                    if (it is Merchant) {
                        if ((it.latitude == atmItemCoordinates.latitude) && (it.longitude == atmItemCoordinates.longitude)) {
                            viewModel.openMerchantDetails(it)
                        }
                    }
                }
                true
            }
        }
    }

    private fun checkPermissionOrShowMap() {
        if (isGooglePlayServicesAvailable()) {
            markerManager = MarkerManager(googleMap)
            markerCollection = markerManager?.newCollection()
            circleManager = googleMap?.let { CircleManager(it) }
            circleCollection = circleManager?.newCollection()

            if (isForegroundLocationPermissionGranted()) {
                viewModel.monitorUserLocation()
                viewModel.observeCurrentUserLocation.observe(viewLifecycleOwner) {
                    showLocationOnMap(it)
                }
            } else {
                permissionRequestLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            googleMap?.uiSettings?.isZoomControlsEnabled = true
        }
    }

    private fun addCircleAroundCurrentPosition() {
        currentLocationCircle = circleCollection?.addCircle(CircleOptions().apply {
            center(mCurrentUserLocation)
            radius(1500.0)
            fillColor(Color.parseColor("#26008DE4"))
            strokeColor(Color.TRANSPARENT)
        })
    }

    private fun addMarkerOnCurrentPosition() {
        val bitmap = getBitmapFromDrawable(R.drawable.user_location_map_marker)
        currentLocationMarker = markerCollection?.addMarker(MarkerOptions().apply {
            position(mCurrentUserLocation)
            anchor(0.5f, 0.5f)
            icon(BitmapDescriptorFactory.fromBitmap(bitmap))
            draggable(true)
        })
        currentLocationMarker?.tag = CURRENT_POSITION_MARKER_TAG
    }

    private fun showLocationOnMap(userLocation: UserLocation) {
        mCurrentUserLocation = LatLng(userLocation.latitude, userLocation.longitude)
        if (currentLocationCircle != null) circleCollection?.remove(currentLocationCircle)
        if (currentLocationMarker != null) markerCollection?.remove(currentLocationMarker)

        addMarkerOnCurrentPosition()
        addCircleAroundCurrentPosition()
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(mCurrentUserLocation, calculateZoomLevel().toFloat()))
    }

    private fun isForegroundLocationPermissionGranted() = ActivityCompat.checkSelfPermission(requireActivity(),
            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isGooglePlayServicesAvailable(): Boolean {
        val googleApiAvailability: GoogleApiAvailability = GoogleApiAvailability.getInstance()
        val status: Int = googleApiAvailability.isGooglePlayServicesAvailable(requireActivity())
        if (ConnectionResult.SUCCESS === status) return true else {
            if (googleApiAvailability.isUserResolvableError(status))
                Toast.makeText(requireActivity(), R.string.common_google_play_services_install_title, Toast.LENGTH_LONG).show()
        }
        return false
    }

    private fun createAppSettingsIntent() = Intent().apply {
        action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        data = Uri.fromParts("package", requireContext().packageName, null)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }

    private fun getBitmapFromDrawable(drawableId: Int): Bitmap {
        var drawable =  AppCompatResources.getDrawable(requireActivity(), drawableId)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            drawable = (DrawableCompat.wrap(drawable!!)).mutate()
        }
        val bitmap = Bitmap.createBitmap(drawable!!.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun milesToMeters(miles: Double): Double {
        return miles * 1609.344
    }

    /**
     * At zoom level 1, the equator of the Earth is 256 pixels long.
     * Every step up in zoom level doubles the number of pixels needed to represent earth's equator
     * The function below provides zoom level where the the screen will show an area of 50 mile radius
     */
    private fun calculateZoomLevel(): Int {
        val equatorLength = 40075004.0
        val displayMetrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        val widthInPixels = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R){
            requireActivity().windowManager.defaultDisplay.getMetrics(displayMetrics)
            displayMetrics.widthPixels
        } else {
            val windowMetrics = requireActivity().windowManager.currentWindowMetrics
            val insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            windowMetrics.bounds.width().minus(insets.left).minus(insets.right)
        }

        var metersPerPixel = equatorLength / 256
        var zoomLevel = 1
        while ((metersPerPixel * widthInPixels) > milesToMeters(50.0 * 2)) {
            metersPerPixel /= 2
            ++zoomLevel
        }
        return zoomLevel
    }
}