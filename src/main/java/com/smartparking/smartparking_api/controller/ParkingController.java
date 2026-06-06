package com.smartparking.smartparking_api.controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.sql.*;
import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ParkingController {

    private static final String URL = "jdbc:mysql://localhost:3306/smartparkingdb";
    private static final String USER = "root";
    private static final String PASSWORD = "aliadmin1234";

    private Connection getConn() throws SQLException {
        Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
        conn.createStatement().executeUpdate("SET SQL_SAFE_UPDATES = 0");
        return conn;
    }

    // LOGIN
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        try {
            Connection conn = getConn();
            PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM users WHERE username=? AND password=?");
            ps.setString(1, body.get("username"));
            ps.setString(2, body.get("password"));
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Map<String, String> res = new HashMap<>();
                res.put("username", rs.getString("username"));
                res.put("role", rs.getString("role"));
                return ResponseEntity.ok(res);
            }
            return ResponseEntity.status(401).body("Invalid credentials");
        } catch (Exception e) {
            return ResponseEntity.status(500).body(e.getMessage());
        }
    }

    // PARK VEHICLE - Auto assign slot
    @PostMapping("/park")
    public ResponseEntity<?> park(@RequestBody Map<String, String> body) {
        try {
            Connection conn = getConn();
            String vehicleType = body.get("vehicle_type");

            // Get all occupied slots
            ResultSet occupiedRs = conn.createStatement().executeQuery(
                    "SELECT slot_id FROM active_tickets");
            Set<String> occupiedSlots = new HashSet<>();
            while (occupiedRs.next()) {
                occupiedSlots.add(occupiedRs.getString("slot_id"));
            }

            // Auto assign slot based on vehicle type
            String prefix = vehicleType.equals("BIKE") ? "B-" : vehicleType.equals("TRUCK") ? "T-" : "C-";
            int maxSlots = vehicleType.equals("BIKE") ? 50 : vehicleType.equals("TRUCK") ? 25 : 30;

            String assignedSlot = null;
            for (int i = 1; i <= maxSlots; i++) {
                String slotId = String.format("%s%02d", prefix, i);
                if (!occupiedSlots.contains(slotId)) {
                    assignedSlot = slotId;
                    break;
                }
            }

            if (assignedSlot == null) {
                return ResponseEntity.status(400).body("No available slots for " + vehicleType);
            }

            String ticketId = "TKT-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO active_tickets (ticket_id, plate_number, slot_id, entry_time, vehicle_type, owner_name, owner_phone) VALUES (?, ?, ?, NOW(), ?, ?, ?)");
            ps.setString(1, ticketId);
            ps.setString(2, body.get("plate_number"));
            ps.setString(3, assignedSlot);
            ps.setString(4, vehicleType);
            ps.setString(5, body.get("owner_name"));
            ps.setString(6, body.get("owner_phone"));
            ps.executeUpdate();

            Map<String, String> res = new HashMap<>();
            res.put("ticket_id", ticketId);
            res.put("slot_id", assignedSlot);
            res.put("message", "Parked Successfully!");
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(e.getMessage());
        }
    }

    // UNPARK VEHICLE
    @PostMapping("/unpark")
    public ResponseEntity<?> unpark(@RequestBody Map<String, String> body) {
        try {
            Connection conn = getConn();
            PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM active_tickets WHERE plate_number=?");
            ps.setString(1, body.get("plate_number"));
            ResultSet rs = ps.executeQuery();

            if (!rs.next()) return ResponseEntity.status(404).body("Vehicle not found!");

            String ticketId = rs.getString("ticket_id");
            String slotId = rs.getString("slot_id");
            String vehicleType = rs.getString("vehicle_type");
            String ownerName = rs.getString("owner_name");
            String ownerPhone = rs.getString("owner_phone");
            Timestamp entryTime = rs.getTimestamp("entry_time");

            long minutes = (System.currentTimeMillis() - entryTime.getTime()) / 60000;
            double fee = Math.max(1, minutes / 60.0) * 100;

            PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO ticket_history (ticket_id, plate_number, slot_id, entry_time, exit_time, fee, vehicle_type, owner_name, owner_phone) VALUES (?, ?, ?, ?, NOW(), ?, ?, ?, ?)");
            ins.setString(1, ticketId);
            ins.setString(2, body.get("plate_number"));
            ins.setString(3, slotId);
            ins.setTimestamp(4, entryTime);
            ins.setDouble(5, fee);
            ins.setString(6, vehicleType);
            ins.setString(7, ownerName);
            ins.setString(8, ownerPhone);
            ins.executeUpdate();

            conn.createStatement().executeUpdate(
                    "DELETE FROM active_tickets WHERE plate_number='" + body.get("plate_number") + "'");

            Map<String, Object> res = new HashMap<>();
            res.put("message", "Vehicle Exited Successfully!");
            res.put("fee", fee);
            res.put("ticket_id", ticketId);
            res.put("slot_id", slotId);
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(e.getMessage());
        }
    }

    // GET ALL ACTIVE TICKETS
    @GetMapping("/active-tickets")
    public ResponseEntity<?> getActiveTickets() {
        try {
            Connection conn = getConn();
            ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM active_tickets ORDER BY entry_time DESC");
            List<Map<String, Object>> list = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                row.put("ticket_id", rs.getString("ticket_id"));
                row.put("plate_number", rs.getString("plate_number"));
                row.put("slot_id", rs.getString("slot_id"));
                row.put("entry_time", rs.getString("entry_time"));
                row.put("vehicle_type", rs.getString("vehicle_type"));
                row.put("owner_name", rs.getString("owner_name"));
                row.put("owner_phone", rs.getString("owner_phone"));
                list.add(row);
            }
            return ResponseEntity.ok(list);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(e.getMessage());
        }
    }

    // GET TICKET HISTORY
    @GetMapping("/history")
    public ResponseEntity<?> getHistory() {
        try {
            Connection conn = getConn();
            ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM ticket_history ORDER BY exit_time DESC");
            List<Map<String, Object>> list = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                row.put("ticket_id", rs.getString("ticket_id"));
                row.put("plate_number", rs.getString("plate_number"));
                row.put("slot_id", rs.getString("slot_id"));
                row.put("entry_time", rs.getString("entry_time"));
                row.put("exit_time", rs.getString("exit_time"));
                row.put("fee", rs.getDouble("fee"));
                row.put("vehicle_type", rs.getString("vehicle_type"));
                row.put("owner_name", rs.getString("owner_name"));
                list.add(row);
            }
            return ResponseEntity.ok(list);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(e.getMessage());
        }
    }

    // REVENUE REPORT
    @GetMapping("/revenue")
    public ResponseEntity<?> getRevenue() {
        try {
            Connection conn = getConn();
            ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT vehicle_type, SUM(fee) as revenue, COUNT(*) as count FROM ticket_history GROUP BY vehicle_type");
            List<Map<String, Object>> list = new ArrayList<>();
            double total = 0;
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                row.put("vehicle_type", rs.getString("vehicle_type"));
                row.put("revenue", rs.getDouble("revenue"));
                row.put("count", rs.getInt("count"));
                total += rs.getDouble("revenue");
                list.add(row);
            }
            Map<String, Object> res = new HashMap<>();
            res.put("breakdown", list);
            res.put("total", total);
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(e.getMessage());
        }
    }

    // STATS
    @GetMapping("/stats")
    public ResponseEntity<?> getStats() {
        try {
            Connection conn = getConn();
            Map<String, Object> stats = new HashMap<>();
            ResultSet total = conn.createStatement().executeQuery("SELECT COUNT(*) as count FROM active_tickets");
            if (total.next()) stats.put("totalParked", total.getInt("count"));
            ResultSet revenue = conn.createStatement().executeQuery("SELECT COALESCE(SUM(fee),0) as total FROM ticket_history");
            if (revenue.next()) stats.put("todayRevenue", revenue.getDouble("total"));
            ResultSet totalVehicles = conn.createStatement().executeQuery("SELECT COUNT(*) as count FROM ticket_history");
            if (totalVehicles.next()) stats.put("totalVehicles", totalVehicles.getInt("count"));
            stats.put("availableSlots", 105);
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(e.getMessage());
        }
    }

    // RECENT ACTIVITY - Entry + Exit
    @GetMapping("/tickets/recent")
    public ResponseEntity<?> getRecentTickets() {
        try {
            Connection conn = getConn();
            List<Map<String, Object>> list = new ArrayList<>();

            ResultSet entries = conn.createStatement().executeQuery(
                    "SELECT ticket_id, plate_number, entry_time, vehicle_type, owner_name FROM active_tickets ORDER BY entry_time DESC LIMIT 5");
            while (entries.next()) {
                Map<String, Object> row = new HashMap<>();
                row.put("ticket_id", entries.getString("ticket_id"));
                row.put("plate_number", entries.getString("plate_number"));
                row.put("entry_time", entries.getString("entry_time"));
                row.put("vehicle_type", entries.getString("vehicle_type"));
                row.put("owner_name", entries.getString("owner_name"));
                row.put("action", "Entry");
                list.add(row);
            }

            ResultSet exits = conn.createStatement().executeQuery(
                    "SELECT ticket_id, plate_number, exit_time, vehicle_type, owner_name FROM ticket_history ORDER BY exit_time DESC LIMIT 5");
            while (exits.next()) {
                Map<String, Object> row = new HashMap<>();
                row.put("ticket_id", exits.getString("ticket_id"));
                row.put("plate_number", exits.getString("plate_number"));
                row.put("entry_time", exits.getString("exit_time"));
                row.put("vehicle_type", exits.getString("vehicle_type"));
                row.put("owner_name", exits.getString("owner_name"));
                row.put("action", "Exit");
                list.add(row);
            }

            list.sort((a, b) -> String.valueOf(b.get("entry_time")).compareTo(String.valueOf(a.get("entry_time"))));
            if (list.size() > 8) list = list.subList(0, 8);
            return ResponseEntity.ok(list);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(e.getMessage());
        }
    }

    // SLOT STATUS
    @GetMapping("/slots/status")
    public ResponseEntity<?> getSlotsStatus() {
        try {
            Connection conn = getConn();
            ResultSet rs = conn.createStatement().executeQuery("SELECT slot_id FROM active_tickets");
            Set<String> occupied = new HashSet<>();
            while (rs.next()) occupied.add(rs.getString("slot_id"));

            List<Map<String, Object>> slots = new ArrayList<>();
            for (int i = 1; i <= 30; i++) {
                String id = String.format("C-%02d", i);
                Map<String, Object> s = new HashMap<>();
                s.put("slot_id", id); s.put("type", "CAR");
                s.put("status", occupied.contains(id) ? "OCCUPIED" : "FREE");
                s.put("floor", i <= 10 ? 1 : i <= 20 ? 2 : 3);
                slots.add(s);
            }
            for (int i = 1; i <= 50; i++) {
                String id = String.format("B-%02d", i);
                Map<String, Object> s = new HashMap<>();
                s.put("slot_id", id); s.put("type", "BIKE");
                s.put("status", occupied.contains(id) ? "OCCUPIED" : "FREE");
                s.put("floor", i <= 17 ? 1 : i <= 34 ? 2 : 3);
                slots.add(s);
            }
            for (int i = 1; i <= 25; i++) {
                String id = String.format("T-%02d", i);
                Map<String, Object> s = new HashMap<>();
                s.put("slot_id", id); s.put("type", "TRUCK");
                s.put("status", occupied.contains(id) ? "OCCUPIED" : "FREE");
                s.put("floor", i <= 9 ? 1 : i <= 17 ? 2 : 3);
                slots.add(s);
            }
            return ResponseEntity.ok(slots);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(e.getMessage());
        }
    }

    // GET RESERVED SLOTS
    @GetMapping("/reserved")
    public ResponseEntity<?> getReserved() {
        try {
            Connection conn = getConn();
            ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM reserved_slots ORDER BY reserved_time DESC");
            List<Map<String, Object>> list = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                row.put("id", rs.getInt("id"));
                row.put("slot_id", rs.getString("slot_id"));
                row.put("reserved_by", rs.getString("reserved_by"));
                row.put("reserved_phone", rs.getString("reserved_phone"));
                row.put("reserved_time", rs.getString("reserved_time"));
                list.add(row);
            }
            return ResponseEntity.ok(list);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(e.getMessage());
        }
    }

    // RESERVE SLOT
    @PostMapping("/reserve")
    public ResponseEntity<?> reserveSlot(@RequestBody Map<String, String> body) {
        try {
            Connection conn = getConn();
            String sql = "INSERT INTO reserved_slots (slot_id, reserved_by, reserved_phone, reserved_time) VALUES (?, ?, ?, NOW())";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, body.get("slot_id"));
            ps.setString(2, body.get("reserved_by"));
            ps.setString(3, body.get("reserved_phone"));
            ps.executeUpdate();
            return ResponseEntity.ok("Slot reserved successfully!");
        } catch (Exception e) {
            return ResponseEntity.status(500).body(e.getMessage());
        }
    }
}