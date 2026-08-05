# Serial SELECT queries (old_serials)

Three mutually exclusive buckets:

1. Serials starting with **M**
2. Seller **S** range (**S440000–S450000**)
3. Everything else

Seller **S** range matches [`isSToMRange`](crm-php/crm/serial-utils.php). Table assumed: `old_serials` (change if you use another). Matching is case-insensitive via `UPPER(...)`.

---

## 1) Serials starting with M

```sql
SELECT *
FROM old_serials
WHERE UPPER(TRIM(serial)) LIKE 'M%'
ORDER BY serial;
```

---

## 2) Seller S serials (S440000–S450000)

```sql
SELECT *
FROM old_serials
WHERE UPPER(TRIM(serial)) REGEXP '^S[0-9]+$'
  AND CAST(SUBSTRING(UPPER(TRIM(serial)), 2) AS UNSIGNED) BETWEEN 440000 AND 450000
ORDER BY serial;
```

---

## 3) The rest (not M%, not seller S range)

```sql
SELECT *
FROM old_serials
WHERE NOT (UPPER(TRIM(serial)) LIKE 'M%')
  AND NOT (
    UPPER(TRIM(serial)) REGEXP '^S[0-9]+$'
    AND CAST(SUBSTRING(UPPER(TRIM(serial)), 2) AS UNSIGNED) BETWEEN 440000 AND 450000
  )
ORDER BY serial;
```
