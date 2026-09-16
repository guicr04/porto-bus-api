package pt.porto.bus.model;

import java.util.List;

/** @param selectedDate YYYYMMDD as returned upstream */
public record StopServices(List<ServiceDay> services, String activeServiceId, String selectedDate, String today) {}
