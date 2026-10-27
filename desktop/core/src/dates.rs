//! Calendar days as "yyyy-MM-dd" strings, the keys of the reading logs, without a date library. The app
//! supplies today's local date; everything else is arithmetic on day numbers.

/// Days since 1970-01-01 for a civil date (Howard Hinnant's algorithm).
pub fn days_from_civil(y: i32, m: u32, d: u32) -> i64 {
    let y = if m <= 2 { y - 1 } else { y } as i64;
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let mp = (m as i64 + 9) % 12;
    let doy = (153 * mp + 2) / 5 + d as i64 - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146097 + doe - 719468
}

pub fn civil_from_days(z: i64) -> (i32, u32, u32) {
    let z = z + 719468;
    let era = if z >= 0 { z } else { z - 146096 } / 146097;
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    ((if m <= 2 { y + 1 } else { y }) as i32, m, d)
}

pub fn key(day: i64) -> String {
    let (y, m, d) = civil_from_days(day);
    format!("{y:04}-{m:02}-{d:02}")
}

pub fn parse(key: &str) -> Option<i64> {
    let mut it = key.splitn(3, '-');
    let y = it.next()?.parse().ok()?;
    let m = it.next()?.parse().ok()?;
    let d = it.next()?.parse().ok()?;
    Some(days_from_civil(y, m, d))
}

/// ISO weekday, Monday = 1 … Sunday = 7.
pub fn weekday(day: i64) -> u32 {
    ((day + 3).rem_euclid(7) + 1) as u32
}

/// One-letter weekday label for charts.
pub fn narrow_weekday(day: i64) -> &'static str {
    ["M", "T", "W", "T", "F", "S", "S"][weekday(day) as usize - 1]
}

pub fn short_month(m: u32) -> &'static str {
    [
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    ][(m as usize).clamp(1, 12) - 1]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip() {
        for k in ["1970-01-01", "2000-02-29", "2026-10-27", "2027-01-01"] {
            assert_eq!(key(parse(k).unwrap()), k);
        }
        assert_eq!(weekday(parse("2026-10-26").unwrap()), 1); // a Monday
        assert_eq!(weekday(parse("1970-01-01").unwrap()), 4); // a Thursday
    }
}
