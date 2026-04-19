package com.example.wearableai.shared

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * FM Global Pre-Incident Plan form — structured data model matching the 3-page
 * paper checklist. Each field has an ID the voice agent uses to fill it via the
 * `fill_field` tool.
 */

enum class FormSection(val heading: String, val page: Int) {
    // Page 1 — Data Checklist
    HEADER("General Information", 1),
    BUILDING_ACCESS("Building Access", 1),
    WATER_SUPPLY("Water Supply", 1),
    BUILDING("Building", 1),
    STORAGE_HAZMAT("Storage Arrangements and Hazardous Material Located on Property", 1),

    // Page 2 — Fire Protection
    FIRE_ALARM("Fire Alarm System", 2),
    FIRE_PUMPS("Fire Pumps", 2),
    SPRINKLER("Standpipe and Automatic Sprinkler Systems", 2),
    SPECIAL_SUPPRESSION("Special Suppression Systems", 2),
    SYSTEM_IMPAIRMENTS("System Impairments and Notes", 2),

    // Page 3 — Worksheet
    FIRST_ALARM("Recommended First Alarm Assignments", 3),
    INCIDENT_COMMAND("Key Considerations for Incident Command", 3),
    NOTES("Notes", 3),
    SITE_RESOURCES("Site-Specific Resources Available", 3),
    COMPLETED("Completion", 3),
}

data class FormField(
    val id: String,
    val label: String,
    val section: FormSection,
    val value: String = "",
    val photoPath: String? = null,
)

/**
 * All fields across the 3-page FM Global Pre-Incident Plan.
 * The field IDs are what the AI agent uses in `fill_field` calls.
 */
val FORM_FIELD_DEFINITIONS: List<FormField> = listOf(
    // Page 1 — Header
    FormField("address", "Address", FormSection.HEADER),
    FormField("contact", "Contact", FormSection.HEADER),
    FormField("phone", "Phone", FormSection.HEADER),
    FormField("business_name_occupancy", "Business Name and Type of Occupancy", FormSection.HEADER),

    // Page 1 — Building Access
    FormField("primary_entrance", "Primary entrance", FormSection.BUILDING_ACCESS),
    FormField("key_box", "Key Box?", FormSection.BUILDING_ACCESS),
    FormField("other_entrances", "Other entrances", FormSection.BUILDING_ACCESS),
    FormField("a_side", "Identify \"A\" side of building", FormSection.BUILDING_ACCESS),

    // Page 1 — Water Supply
    FormField("available_hydrants", "Available hydrants", FormSection.WATER_SUPPLY),
    FormField("available_water_sources", "Available water sources", FormSection.WATER_SUPPLY),
    FormField("private_water_main", "Private water main system on property?", FormSection.WATER_SUPPLY),

    // Page 1 — Building
    FormField("exterior_wall_construction", "Exterior wall construction", FormSection.BUILDING),
    FormField("number_of_floors", "Number of floors", FormSection.BUILDING),
    FormField("basement", "Basement", FormSection.BUILDING),
    FormField("sub_basement", "Sub-basement", FormSection.BUILDING),
    FormField("structural_members", "Structural members", FormSection.BUILDING),
    FormField("structural_members_exposed", "Structural members exposed/protected?", FormSection.BUILDING),
    FormField("truss_construction", "Truss construction? (describe type and location)", FormSection.BUILDING),
    FormField("fire_walls_openings", "Condition of fire walls and openings", FormSection.BUILDING),
    FormField("ceiling_type", "Ceiling type", FormSection.BUILDING),
    FormField("utilities", "Utilities (type and entrance point into building)", FormSection.BUILDING),
    FormField("hazards_to_firefighters", "Hazards to firefighters in building/location (pits, fall hazards, etc.)", FormSection.BUILDING),

    // Page 1 — Storage/Hazmat
    FormField("storage_hazmat", "Storage arrangements and hazardous materials", FormSection.STORAGE_HAZMAT),

    // Page 2 — Fire Alarm
    FormField("fa_annunciators_location", "Location of FA annunciators", FormSection.FIRE_ALARM),
    FormField("central_station_fa_box", "Central station/FA box", FormSection.FIRE_ALARM),

    // Page 2 — Fire Pumps
    FormField("fire_pumps_location", "Location", FormSection.FIRE_PUMPS),
    FormField("fire_pumps_type", "Type", FormSection.FIRE_PUMPS),

    // Page 2 — Sprinkler Systems (4 rows)
    FormField("sprinkler_1_type", "System 1 — Type of System", FormSection.SPRINKLER),
    FormField("sprinkler_1_valves", "System 1 — Location of Control Valves", FormSection.SPRINKLER),
    FormField("sprinkler_1_area", "System 1 — Area Protected", FormSection.SPRINKLER),
    FormField("sprinkler_1_fdc", "System 1 — Location of FDC/Size/Distance to Water Supply", FormSection.SPRINKLER),
    FormField("sprinkler_2_type", "System 2 — Type of System", FormSection.SPRINKLER),
    FormField("sprinkler_2_valves", "System 2 — Location of Control Valves", FormSection.SPRINKLER),
    FormField("sprinkler_2_area", "System 2 — Area Protected", FormSection.SPRINKLER),
    FormField("sprinkler_2_fdc", "System 2 — Location of FDC/Size/Distance to Water Supply", FormSection.SPRINKLER),
    FormField("sprinkler_3_type", "System 3 — Type of System", FormSection.SPRINKLER),
    FormField("sprinkler_3_valves", "System 3 — Location of Control Valves", FormSection.SPRINKLER),
    FormField("sprinkler_3_area", "System 3 — Area Protected", FormSection.SPRINKLER),
    FormField("sprinkler_3_fdc", "System 3 — Location of FDC/Size/Distance to Water Supply", FormSection.SPRINKLER),
    FormField("sprinkler_4_type", "System 4 — Type of System", FormSection.SPRINKLER),
    FormField("sprinkler_4_valves", "System 4 — Location of Control Valves", FormSection.SPRINKLER),
    FormField("sprinkler_4_area", "System 4 — Area Protected", FormSection.SPRINKLER),
    FormField("sprinkler_4_fdc", "System 4 — Location of FDC/Size/Distance to Water Supply", FormSection.SPRINKLER),

    // Page 2 — Special Suppression
    FormField("special_suppression", "Special Suppression Systems (type, location, and coverage)", FormSection.SPECIAL_SUPPRESSION),

    // Page 2 — Impairments
    FormField("system_impairments", "System Impairments and Notes", FormSection.SYSTEM_IMPAIRMENTS),

    // Page 3 — Worksheet
    FormField("first_alarm_assignments", "Recommended First Alarm Assignments", FormSection.FIRST_ALARM),
    FormField("incident_command", "Key Considerations for Incident Command", FormSection.INCIDENT_COMMAND),
    FormField("worksheet_notes", "Notes", FormSection.NOTES),
    FormField("site_resources", "Site-Specific Resources Available", FormSection.SITE_RESOURCES),
    FormField("completed_by", "Completed by", FormSection.COMPLETED),
    FormField("completion_date", "Date", FormSection.COMPLETED),
    FormField("building_plan_attached", "Building plan attached", FormSection.COMPLETED),
)

/** All valid field IDs for tool enum validation. */
val FORM_FIELD_IDS: List<String> = FORM_FIELD_DEFINITIONS.map { it.id }

/**
 * Mutable form state. Thread-safe via copy-on-write StateFlow.
 */
class PreIncidentFormState {
    private val _fields = MutableStateFlow(FORM_FIELD_DEFINITIONS.associateBy { it.id })
    val fields: StateFlow<Map<String, FormField>> = _fields.asStateFlow()

    fun fillField(fieldId: String, value: String, photoPath: String? = null): Boolean {
        val current = _fields.value
        val existing = current[fieldId] ?: return false
        _fields.value = current + (fieldId to existing.copy(value = value, photoPath = photoPath ?: existing.photoPath))
        return true
    }

    fun appendField(fieldId: String, value: String, photoPath: String? = null): Boolean {
        val current = _fields.value
        val existing = current[fieldId] ?: return false
        val newVal = if (existing.value.isBlank()) value else "${existing.value}; $value"
        _fields.value = current + (fieldId to existing.copy(value = newVal, photoPath = photoPath ?: existing.photoPath))
        return true
    }

    fun getField(fieldId: String): FormField? = _fields.value[fieldId]

    fun filledCount(): Int = _fields.value.values.count { it.value.isNotBlank() }
    fun totalCount(): Int = _fields.value.size

    fun clear() {
        _fields.value = FORM_FIELD_DEFINITIONS.associateBy { it.id }
    }

    /** Render current form state as markdown for the AI to see what's filled. */
    fun render(): String {
        val sb = StringBuilder()
        var currentSection: FormSection? = null
        for (def in FORM_FIELD_DEFINITIONS) {
            val field = _fields.value[def.id] ?: continue
            if (field.section != currentSection) {
                currentSection = field.section
                sb.append("\n## ${currentSection.heading} (Page ${currentSection.page})\n")
            }
            val status = if (field.value.isNotBlank()) field.value else "___"
            sb.append("- **${field.label}**: $status")
            if (field.photoPath != null) sb.append(" [photo]")
            sb.append("\n")
        }
        return sb.toString().trimStart()
    }

    /** Return list of unfilled field labels for gap analysis. */
    fun gaps(): List<String> =
        FORM_FIELD_DEFINITIONS.filter { (_fields.value[it.id]?.value ?: "").isBlank() }.map { it.label }
}
