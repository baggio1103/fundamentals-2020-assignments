/*
 * Copyright (C) 2020 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.people.ui.chat

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.transition.TransitionInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.observe
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.android.people.R
import com.example.android.people.VoiceCallActivity
import com.example.android.people.getNavigationController
import com.example.android.people.handle

/**
 * The chat screen. This is used in the full app (MainActivity) as well as in the expanded Bubble
 * (BubbleActivity).
 */
class ChatFragment : Fragment(R.layout.chat_fragment) {

    private val viewModel: ChatViewModel by viewModels()
    
    private var rvMessages: RecyclerView? = null
    private var etChatInput: ChatEditText? = null
    private var ivPhoto: ImageView? = null
    private var voiceCallButton: ImageButton? = null
    private var sendButton: ImageButton? = null

    // Ws04_permissions
    private var sendLocationButton: ImageButton? = null
    private var isRationaleShown = false
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>

    @SuppressLint("MissingPermission")
    override fun onAttach(context: Context) {
        super.onAttach(context)
    
        // TODO Ws04_Permissions_02: Uncomment, register callback.
        //  - If permission "isGranted", call [onLocationPermissionGranted()];
        //  - If permission not granted, call [onLocationPermissionNotGranted()];
        requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                onLocationPermissionGranted()

            } else {
                onLocationPermissionNotGranted()
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        enterTransition =
            TransitionInflater.from(context).inflateTransition(R.transition.slide_bottom)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val id = arguments?.getLong(ARG_ID)
        if (id == null) {
            parentFragmentManager.popBackStack()
            return
        }
        val prepopulateText = arguments?.getString(ARG_PREPOPULATE_TEXT)
        val navigationController = getNavigationController()
        
        restorePreferencesData()

        viewModel.setChatId(id)

        val messageAdapter = MessageAdapter(view.context) { uri ->
            navigationController.openPhoto(uri)
        }
        val linearLayoutManager = LinearLayoutManager(view.context).apply {
            stackFromEnd = true
        }
        
        rvMessages = view.findViewById<RecyclerView>(R.id.messagesRecycler).apply {
            layoutManager = linearLayoutManager
            adapter = messageAdapter
        }

        viewModel.contact.observe(viewLifecycleOwner) { contact ->
            if (contact == null) {
                Toast.makeText(view.context, "Contact not found", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
                
            } else {
                navigationController.updateAppBar { name, icon ->
                    name.text = contact.name
                    Glide
                        .with(this)
                        .load(contact.iconUri)
                        .circleCrop()
                        .into(icon)
                    startPostponedEnterTransition()
                }
            }
        }

        viewModel.messages.observe(viewLifecycleOwner) { messages ->
            messageAdapter.submitList(messages) {
                linearLayoutManager.scrollToPosition(messages.size - 1)
            }
        }

        viewModel.events.handle(viewLifecycleOwner) { event ->
            when (event) {
                is ChatViewModel.Event.Error ->
                    Toast.makeText(view.context, getString(event.textResource), Toast.LENGTH_SHORT)
                        .show()
                ChatViewModel.Event.LocationProviderDisabled -> showLocationProviderSettingsDialog()
            }
        }

        etChatInput = view.findViewById<ChatEditText>(R.id.input).apply {
            if (prepopulateText != null) {
                setText(prepopulateText)
            }

            setOnImageAddedListener { contentUri, mimeType, label ->
                viewModel.setPhoto(contentUri, mimeType)
                if (text.isNullOrBlank()) {
                    setText(label)
                }
            }
    
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    send(text)
                    true
                    
                } else {
                    false
                }
            }
        }

        ivPhoto = view.findViewById(R.id.photo)
        viewModel.photo.observe(viewLifecycleOwner) { uri ->
            if (uri == null) {
                ivPhoto?.visibility = View.GONE
                
            } else {
                ivPhoto?.apply {
                    visibility = View.VISIBLE
                    Glide.with(context).load(uri).into(this)
                }
            }
        }
    
        voiceCallButton = view.findViewById<ImageButton>(R.id.voice_call).apply {
            setOnClickListener {
                voiceCall()
            }
        }
        
        sendLocationButton = view.findViewById<ImageButton>(R.id.sendLocationsButton).apply {
            setOnClickListener {
                onSendLocation()
            }
        }
        
        sendButton = view.findViewById<ImageButton>(R.id.send).apply {
            setOnClickListener {
                send(etChatInput?.text)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val foreground = arguments?.getBoolean(ARG_FOREGROUND) == true
        viewModel.foreground = foreground
    }
    
    override fun onStop() {
        super.onStop()
        viewModel.foreground = false
    }
    
    override fun onDestroyView() {
        clearViews()
        savePreferencesData()
        
        super.onDestroyView()
    }
    
    override fun onDetach() {
        // TODO Ws04_Permissions_03: Uncomment, call "unregister()" function.
        requestPermissionLauncher.unregister()
        
        super.onDetach()
    }
    
    private fun clearViews() {
        rvMessages?.adapter = null
        rvMessages = null
        etChatInput = null
        ivPhoto = null
        voiceCallButton = null
        sendLocationButton = null
        sendButton = null
    }

    private fun voiceCall() {
        val contact = viewModel.contact.value ?: return
        startActivity(
            Intent(requireActivity(), VoiceCallActivity::class.java)
                .putExtra(VoiceCallActivity.EXTRA_NAME, contact.name)
                .putExtra(VoiceCallActivity.EXTRA_ICON, contact.icon)
        )
    }

    private fun send(text: Editable?) {
        text?.let {
            if (it.isNotEmpty()) {
                viewModel.send(it.toString())
                it.clear()
            }
        }
    }
    
    private fun savePreferencesData() {
        activity?.let {
            it.getPreferences(MODE_PRIVATE).edit()
                .putBoolean(KEY_LOCATION_PERMISSION_RATIONALE_SHOWN, isRationaleShown)
                .apply()
        }
    }
    
    private fun restorePreferencesData() {
        isRationaleShown = activity?.getPreferences(MODE_PRIVATE)?.getBoolean(
            KEY_LOCATION_PERMISSION_RATIONALE_SHOWN,
            false
        ) ?: false
    }
    
    private fun onSendLocation() {
        activity?.let {
            when {
                // TODO Ws04_Permissions_04: Fill "when" operator with variants,
                //  how to handle button click.
                //  - Check permission and send location into chat;
                //  - Otherwise check rationale shown, show dialog, memorize "isRationaleShown = true";
                //  - Otherwise if "isRationaleShown = true", show permission denied dialog;
                //  - Else, request permission.
                ContextCompat.checkSelfPermission(it, Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED -> onLocationPermissionGranted()
                shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION) ->
                    showLocationPermissionExplanationDialog()
                isRationaleShown -> showLocationPermissionDeniedDialog()
                else -> requestLocationPermission()
            }
        }
    }
    
    private fun requestLocationPermission() {
        context?.let {
            // TODO Ws04_Permissions_06: launch permission request from "requestPermissionLauncher".
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    private fun onLocationPermissionGranted() {
        context?.let {
            Toast.makeText(context, R.string.ws04_permission_granted_text, Toast.LENGTH_SHORT).show()
            viewModel.sendLocation()
        }
    }

    private fun onLocationPermissionNotGranted() {
        context?.let {
            Toast.makeText(context, R.string.ws04_permission_not_granted_text, Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showLocationPermissionExplanationDialog() {
        context?.let {
            AlertDialog.Builder(it)
                .setMessage(R.string.ws04_permission_dialog_explanation_text)
                .setPositiveButton(R.string.ws04_dialog_positive_button) { dialog, _ ->
                    // TODO Ws04_Permissions_05: Show rationale explanation dialog.
                    //  On positive click:
                    //  - memorize "isRationaleShown = true";
                    //  - request location permission.
                    isRationaleShown = true
                    requestLocationPermission()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.ws04_dialog_negative_button) { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }
    }
    
    private fun showLocationPermissionDeniedDialog() {
        context?.let {
            AlertDialog.Builder(it)
                .setMessage(R.string.ws04_permission_dialog_denied_text)
                .setPositiveButton(R.string.ws04_dialog_positive_button) { dialog, _ ->
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + it.packageName)
                        )
                    )
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.ws04_dialog_negative_button) { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }
    }
    
    private fun showLocationProviderSettingsDialog() {
        context?.let {
            AlertDialog.Builder(it)
                .setMessage(R.string.ws04_location_provider_not_available_text)
                .setPositiveButton(R.string.ws04_dialog_positive_button) { dialog, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.ws04_dialog_negative_button) { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }
    }
    
    companion object {
        private const val ARG_ID = "id"
        private const val ARG_FOREGROUND = "foreground"
        private const val ARG_PREPOPULATE_TEXT = "prepopulate_text"
        
        private const val KEY_LOCATION_PERMISSION_RATIONALE_SHOWN = "KEY_LOCATION_PERMISSION_RATIONALE_SHOWN_APP"
        
        fun newInstance(id: Long, foreground: Boolean, prepopulateText: String? = null) =
            ChatFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_ID, id)
                    putBoolean(ARG_FOREGROUND, foreground)
                    putString(ARG_PREPOPULATE_TEXT, prepopulateText)
                }
            }
    }
}