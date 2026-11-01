/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.idea.streaming.emulator.dialogs

/** Standard Android Automotive Vehicle HAL (VHAL) property IDs, bitmasks, and enum values. */
internal object VhalPropertyConstants {

  object VehiclePropertyGroup {
    const val SYSTEM = 0x10000000
    const val VENDOR = 0x20000000
    const val MASK = -0x10000000 // 0xf0000000
  }

  object VehicleArea {
    const val GLOBAL = 0x01000000
    const val WINDOW = 0x03000000
    const val MIRROR = 0x04000000
    const val SEAT = 0x05000000
    const val DOOR = 0x06000000
    const val WHEEL = 0x07000000
    const val MASK = 0x0f000000
  }

  object VehiclePropertyAccess {
    const val NONE = 0
    const val READ = 1
    const val WRITE = 2
    const val READ_WRITE = 3
  }

  object VehiclePropertyType {
    const val STRING = 0x00100000
    const val BOOLEAN = 0x00200000
    const val INT32 = 0x00400000
    const val INT32_VEC = 0x00410000
    const val INT64 = 0x00500000
    const val INT64_VEC = 0x00510000
    const val FLOAT = 0x00600000
    const val FLOAT_VEC = 0x00610000
    const val BYTES = 0x00700000
    const val MIXED = 0x00e00000
    const val MASK = 0x00ff0000
  }

  object VehicleProperty {
    const val INVALID = 0
    const val INFO_VIN = 286261504
    const val INFO_MAKE = 286261505
    const val INFO_MODEL = 286261506
    const val INFO_MODEL_YEAR = 289407235
    const val INFO_FUEL_CAPACITY = 291504388
    const val INFO_FUEL_TYPE = 289472773
    const val INFO_EV_BATTERY_CAPACITY = 291504390
    const val INFO_EV_CONNECTOR_TYPE = 289472775
    const val INFO_FUEL_DOOR_LOCATION = 289407240
    const val INFO_EV_PORT_LOCATION = 289407241
    const val INFO_DRIVER_SEAT = 356516106
    const val INFO_EXTERIOR_DIMENSIONS = 289472779
    const val INFO_MULTI_EV_PORT_LOCATIONS = 289472780
    const val PERF_ODOMETER = 291504644
    const val PERF_VEHICLE_SPEED = 291504647
    const val PERF_VEHICLE_SPEED_DISPLAY = 291504648
    const val PERF_STEERING_ANGLE = 291504649
    const val PERF_REAR_STEERING_ANGLE = 291504656
    const val ENGINE_COOLANT_TEMP = 291504897
    const val ENGINE_OIL_LEVEL = 289407747
    const val ENGINE_OIL_TEMP = 291504900
    const val ENGINE_RPM = 291504901
    const val WHEEL_TICK = 290521862
    const val FUEL_LEVEL = 291504903
    const val FUEL_DOOR_OPEN = 287310600
    const val EV_BATTERY_LEVEL = 291504905
    const val EV_CHARGE_PORT_OPEN = 287310602
    const val EV_CHARGE_PORT_CONNECTED = 287310603
    const val EV_BATTERY_INSTANTANEOUS_CHARGE_RATE = 291504908
    const val RANGE_REMAINING = 291504904
    const val TIRE_PRESSURE = 392168201
    const val CRITICALLY_LOW_TIRE_PRESSURE = 392168202
    const val GEAR_SELECTION = 289408000
    const val CURRENT_GEAR = 289408001
    const val PARKING_BRAKE_ON = 287310850
    const val PARKING_BRAKE_AUTO_APPLY = 287310851
    const val FUEL_LEVEL_LOW = 287310853
    const val NIGHT_MODE = 287310855
    const val TURN_SIGNAL_STATE = 289408008
    const val IGNITION_STATE = 289408009
    const val ABS_ACTIVE = 287310858
    const val TRACTION_CONTROL_ACTIVE = 287310859
    const val HVAC_FAN_SPEED = 356517120
    const val HVAC_FAN_DIRECTION = 356517121
    const val HVAC_TEMPERATURE_CURRENT = 358614274
    const val HVAC_TEMPERATURE_SET = 358614275
    const val HVAC_DEFROSTER = 320865540
    const val HVAC_AC_ON = 354419973
    const val HVAC_MAX_AC_ON = 354419974
    const val HVAC_MAX_DEFROST_ON = 354419975
    const val HVAC_RECIRC_ON = 354419976
    const val HVAC_DUAL_ON = 354419977
    const val HVAC_AUTO_ON = 354419978
    const val HVAC_SEAT_TEMPERATURE = 356517131
    const val HVAC_SIDE_MIRROR_HEAT = 339739916
    const val HVAC_STEERING_WHEEL_HEAT = 289408269
    const val HVAC_TEMPERATURE_DISPLAY_UNITS = 289408270
    const val HVAC_ACTUAL_FAN_SPEED_RPM = 356517135
    const val HVAC_POWER_ON = 354419984
    const val HVAC_FAN_DIRECTION_AVAILABLE = 356582673
    const val HVAC_AUTO_RECIRC_ON = 354419986
    const val HVAC_SEAT_VENTILATION = 356517139
    const val HVAC_ELECTRIC_DEFROSTER_ON = 320865556
    const val HVAC_TEMPERATURE_VALUE_SUGGESTION = 291570965
    const val DISTANCE_DISPLAY_UNITS = 289408512
    const val FUEL_VOLUME_DISPLAY_UNITS = 289408513
    const val TIRE_PRESSURE_DISPLAY_UNITS = 289408514
    const val EV_BATTERY_DISPLAY_UNITS = 289408515
    const val FUEL_CONSUMPTION_UNITS_DISTANCE_OVER_VOLUME = 287311364
    const val VEHICLE_SPEED_DISPLAY_UNITS = 289408517
    const val EXTERNAL_CAR_TIME = 290457096
    const val ANDROID_EPOCH_TIME = 290457094
    const val STORAGE_ENCRYPTION_BINDING_SEED = 292554247
    const val ENV_OUTSIDE_TEMPERATURE = 291505923
    const val AP_POWER_STATE_REQ = 289475072
    const val AP_POWER_STATE_REPORT = 289475073
    const val AP_POWER_BOOTUP_REASON = 289409538
    const val DISPLAY_BRIGHTNESS = 289409539
    const val HW_KEY_INPUT = 289475088
    const val HW_ROTARY_INPUT = 289475104
    const val HW_CUSTOM_INPUT = 289475120
    const val DOOR_POS = 373295872
    const val DOOR_MOVE = 373295873
    const val DOOR_LOCK = 371198722
    const val MIRROR_Z_POS = 339741504
    const val MIRROR_Z_MOVE = 339741505
    const val MIRROR_Y_POS = 339741506
    const val MIRROR_Y_MOVE = 339741507
    const val MIRROR_LOCK = 287312708
    const val MIRROR_FOLD = 287312709
    const val SEAT_MEMORY_SELECT = 356518784
    const val SEAT_MEMORY_SET = 356518785
    const val SEAT_BELT_BUCKLED = 354421634
    const val SEAT_BELT_HEIGHT_POS = 356518787
    const val SEAT_BELT_HEIGHT_MOVE = 356518788
    const val SEAT_FORE_AFT_POS = 356518789
    const val SEAT_FORE_AFT_MOVE = 356518790
    const val SEAT_BACKREST_ANGLE_1_POS = 356518791
    const val SEAT_BACKREST_ANGLE_1_MOVE = 356518792
    const val SEAT_BACKREST_ANGLE_2_POS = 356518793
    const val SEAT_BACKREST_ANGLE_2_MOVE = 356518794
    const val SEAT_HEIGHT_POS = 356518795
    const val SEAT_HEIGHT_MOVE = 356518796
    const val SEAT_DEPTH_POS = 356518797
    const val SEAT_DEPTH_MOVE = 356518798
    const val SEAT_TILT_POS = 356518799
    const val SEAT_TILT_MOVE = 356518800
    const val SEAT_LUMBAR_FORE_AFT_POS = 356518801
    const val SEAT_LUMBAR_FORE_AFT_MOVE = 356518802
    const val SEAT_LUMBAR_SIDE_SUPPORT_POS = 356518803
    const val SEAT_LUMBAR_SIDE_SUPPORT_MOVE = 356518804
    const val SEAT_HEADREST_HEIGHT_POS = 289409941
    const val SEAT_HEADREST_HEIGHT_MOVE = 356518806
    const val SEAT_HEADREST_ANGLE_POS = 356518807
    const val SEAT_HEADREST_ANGLE_MOVE = 356518808
    const val SEAT_HEADREST_FORE_AFT_POS = 356518809
    const val SEAT_HEADREST_FORE_AFT_MOVE = 356518810
    const val SEAT_OCCUPANCY = 356518832
    const val WINDOW_POS = 322964416
    const val WINDOW_MOVE = 322964417
    const val WINDOW_LOCK = 320867268
    const val VEHICLE_MAP_SERVICE = 299895808
    const val OBD2_LIVE_FRAME = 299896064
    const val OBD2_FREEZE_FRAME = 299896065
    const val OBD2_FREEZE_FRAME_INFO = 299896066
    const val OBD2_FREEZE_FRAME_CLEAR = 299896067
    const val HEADLIGHTS_STATE = 289410560
    const val HIGH_BEAM_LIGHTS_STATE = 289410561
    const val FOG_LIGHTS_STATE = 289410562
    const val HAZARD_LIGHTS_STATE = 289410563
    const val HEADLIGHTS_SWITCH = 289410576
    const val HIGH_BEAM_LIGHTS_SWITCH = 289410577
    const val FOG_LIGHTS_SWITCH = 289410578
    const val HAZARD_LIGHTS_SWITCH = 289410579
    const val CABIN_LIGHTS_STATE = 289410817
    const val CABIN_LIGHTS_SWITCH = 289410818
    const val READING_LIGHTS_STATE = 356519683
    const val READING_LIGHTS_SWITCH = 356519684
    const val SUPPORT_CUSTOMIZE_VENDOR_PERMISSION = 287313669
    const val DISABLED_OPTIONAL_FEATURES = 286265094
    const val INITIAL_USER_INFO = 299896583
    const val SWITCH_USER = 299896584
    const val CREATE_USER = 299896585
    const val REMOVE_USER = 299896586
    const val USER_IDENTIFICATION_ASSOCIATION = 299896587
    const val EVS_SERVICE_REQUEST = 289476368
    const val POWER_POLICY_REQ = 286265121
    const val POWER_POLICY_GROUP_REQ = 286265122
    const val CURRENT_POWER_POLICY = 286265123
    const val WATCHDOG_ALIVE = 290459441
    const val WATCHDOG_TERMINATED_PROCESS = 299896626
    const val VHAL_HEARTBEAT = 290459443
    const val CLUSTER_SWITCH_UI = 289410868
    const val CLUSTER_DISPLAY_STATE = 289476405
    const val CLUSTER_REPORT_STATE = 299896630
    const val CLUSTER_REQUEST_DISPLAY = 289410871
    const val CLUSTER_NAVIGATION_STATE = 292556600
    const val ELECTRONIC_TOLL_COLLECTION_CARD_TYPE = 289410873
    const val ELECTRONIC_TOLL_COLLECTION_CARD_STATUS = 289410874
    const val FRONT_FOG_LIGHTS_STATE = 289410875
    const val FRONT_FOG_LIGHTS_SWITCH = 289410876
    const val REAR_FOG_LIGHTS_STATE = 289410877
    const val REAR_FOG_LIGHTS_SWITCH = 289410878
    const val EV_CHARGE_CURRENT_DRAW_LIMIT = 291508031
    const val EV_CHARGE_PERCENT_LIMIT = 291508032
    const val EV_CHARGE_STATE = 289410881
    const val EV_CHARGE_SWITCH = 287313730
    const val EV_CHARGE_TIME_REMAINING = 289410883
    const val EV_REGENERATIVE_BRAKING_STATE = 289410884
    const val TRAILER_PRESENT = 289410885
    const val VEHICLE_CURB_WEIGHT = 289410886
    const val GENERAL_SAFETY_REGULATION_COMPLIANCE_REQUIREMENT = 289410887
  }

  object VehicleGear {
    const val GEAR_UNKNOWN = 0x0000
    const val GEAR_NEUTRAL = 0x0001
    const val GEAR_REVERSE = 0x0002
    const val GEAR_PARK = 0x0004
    const val GEAR_DRIVE = 0x0008
    const val GEAR_1 = 0x0010
    const val GEAR_2 = 0x0020
    const val GEAR_3 = 0x0040
    const val GEAR_4 = 0x0080
    const val GEAR_5 = 0x0100
    const val GEAR_6 = 0x0200
    const val GEAR_7 = 0x0400
    const val GEAR_8 = 0x0800
    const val GEAR_9 = 0x1000
  }

  object VehicleIgnitionState {
    const val UNDEFINED = 0
    const val LOCK = 1
    const val OFF = 2
    const val ACC = 3
    const val ON = 4
    const val START = 5
  }

  object FuelType {
    const val FUEL_TYPE_UNKNOWN = 0
    const val FUEL_TYPE_UNLEADED = 1
    const val FUEL_TYPE_LEADED = 2
    const val FUEL_TYPE_DIESEL_1 = 3
    const val FUEL_TYPE_DIESEL_2 = 4
    const val FUEL_TYPE_BIODIESEL = 5
    const val FUEL_TYPE_E85 = 6
    const val FUEL_TYPE_LPG = 7
    const val FUEL_TYPE_CNG = 8
    const val FUEL_TYPE_LNG = 9
    const val FUEL_TYPE_ELECTRIC = 10
    const val FUEL_TYPE_HYDROGEN = 11
    const val FUEL_TYPE_OTHER = 12
  }

  object EvConnectorType {
    const val UNKNOWN = 0
    const val IEC_TYPE_1_AC = 1
    const val IEC_TYPE_2_AC = 2
    const val IEC_TYPE_3_AC = 3
    const val IEC_TYPE_4_DC = 4
    const val IEC_TYPE_1_CCS_DC = 5
    const val IEC_TYPE_2_CCS_DC = 6
    const val TESLA_ROADSTER = 7
    const val TESLA_HPWC = 8
    const val TESLA_CHARGER = 9
    const val GBT_AC = 10
    const val GBT_DC = 11
    const val OTHER = 101
  }

  object PortLocationType {
    const val UNKNOWN = 0
    const val FRONT_LEFT = 1
    const val FRONT_RIGHT = 2
    const val REAR_RIGHT = 3
    const val REAR_LEFT = 4
    const val FRONT = 5
    const val REAR = 6
  }

  object VehicleAreaSeat {
    const val ROW_1_LEFT = 0x0001
    const val ROW_1_CENTER = 0x0002
    const val ROW_1_RIGHT = 0x0004
    const val ROW_2_LEFT = 0x0010
    const val ROW_2_CENTER = 0x0020
    const val ROW_2_RIGHT = 0x0040
    const val ROW_3_LEFT = 0x0100
    const val ROW_3_CENTER = 0x0200
    const val ROW_3_RIGHT = 0x0400
  }

  object VehicleAreaMirror {
    const val DRIVER_LEFT = 0x00000001
    const val DRIVER_RIGHT = 0x00000002
    const val DRIVER_CENTER = 0x00000004
  }

  object VehicleAreaDoor {
    const val ROW_1_LEFT = 0x00000001
    const val ROW_1_RIGHT = 0x00000004
    const val ROW_2_LEFT = 0x00000010
    const val ROW_2_RIGHT = 0x00000040
    const val ROW_3_LEFT = 0x00000100
    const val ROW_3_RIGHT = 0x00000400
    const val HOOD = 0x10000000
    const val REAR = 0x20000000
  }

  object VehicleAreaWindow {
    const val FRONT_WINDSHIELD = 0x00000001
    const val REAR_WINDSHIELD = 0x00000002
    const val ROW_1_LEFT = 0x00000010
    const val ROW_1_RIGHT = 0x00000040
    const val ROW_2_LEFT = 0x00000100
    const val ROW_2_RIGHT = 0x00000400
    const val ROW_3_LEFT = 0x00001000
    const val ROW_3_RIGHT = 0x00004000
    const val ROOF_TOP_1 = 0x00010000
    const val ROOF_TOP_2 = 0x00020000
  }

  object VehicleAreaWheel {
    const val UNKNOWN = 0x0
    const val LEFT_FRONT = 0x1
    const val RIGHT_FRONT = 0x2
    const val LEFT_REAR = 0x4
    const val RIGHT_REAR = 0x8
  }

  object VehicleOilLevel {
    const val CRITICALLY_LOW = 0
    const val LOW = 1
    const val NORMAL = 2
    const val HIGH = 3
    const val ERROR = 4
  }

  object VehicleHvacFanDirection {
    const val UNKNOWN = 0x0
    const val FACE = 0x1
    const val FLOOR = 0x2
    const val DEFROST = 0x4
  }

  object VehicleUnit {
    const val CELSIUS = 48
    const val FAHRENHEIT = 49
    const val KELVIN = 50
    const val MILLILITER = 64
    const val LITER = 65
    const val US_GALLON = 66
    const val IMPERIAL_GALLON = 67
    const val KILOMETER = 35
    const val MILE = 36
    const val METER = 33
    const val MILLIMETER = 32
    const val WATT_HOUR = 96
    const val AMPERE_HOURS = 100
    const val KILOWATT_HOUR = 101
    const val KILOPASCAL = 112
    const val PSI = 113
    const val BAR = 114
    const val METER_PER_SEC = 1
    const val MILES_PER_HOUR = 144
    const val KILOMETERS_PER_HOUR = 145
  }

  object VehicleLightState {
    const val OFF = 0
    const val ON = 1
    const val DAYTIME_RUNNING = 2
  }

  object VehicleLightSwitch {
    const val OFF = 0
    const val ON = 1
    const val DAYTIME_RUNNING = 2
    const val AUTOMATIC = 256
  }

  object GsrComplianceRequirementType {
    const val GSR_COMPLIANCE_NOT_REQUIRED = 0
    const val GSR_COMPLIANCE_REQUIRED_V1 = 1
  }

  object VehicleApPowerStateReq {
    const val ON = 0
    const val SHUTDOWN_PREPARE = 1
    const val CANCEL_SHUTDOWN = 2
    const val FINISHED = 3
  }

  object VehicleApPowerStateShutdownParam {
    const val SHUTDOWN_IMMEDIATELY = 1
    const val CAN_SLEEP = 2
    const val SHUTDOWN_ONLY = 3
    const val SLEEP_IMMEDIATELY = 4
    const val HIBERNATE_IMMEDIATELY = 5
    const val CAN_HIBERNATE = 6
  }

  object VehicleApPowerStateReport {
    const val WAIT_FOR_VHAL = 1
    const val DEEP_SLEEP_ENTRY = 2
    const val DEEP_SLEEP_EXIT = 3
    const val SHUTDOWN_POSTPONE = 4
    const val SHUTDOWN_START = 5
    const val ON = 6
    const val SHUTDOWN_PREPARE = 7
    const val SHUTDOWN_CANCELLED = 8
    const val HIBERNATION_ENTRY = 9
    const val HIBERNATION_EXIT = 10
  }

  object VehicleTurnSignal {
    const val NONE = 0
    const val RIGHT = 1
    const val LEFT = 2
  }

  object VehicleSeatOccupancyState {
    const val UNKNOWN = 0
    const val VACANT = 1
    const val OCCUPIED = 2
  }
}
