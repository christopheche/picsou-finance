import unittest

from portfolio_parser import (
    PortfolioFormatError,
    build_positions,
    build_product_info_map,
    describe_product_info,
    is_real_product_id,
    parse_cash_eur,
    parse_raw_positions,
    sanitize_product_info,
    value_pairs_to_dict,
)


class IsRealProductIdTest(unittest.TestCase):
    def test_numeric_string_is_real(self):
        self.assertTrue(is_real_product_id("15690087"))

    def test_int_is_real(self):
        self.assertTrue(is_real_product_id(15690087))

    def test_flatex_cash_pseudo_position_is_not_real(self):
        self.assertFalse(is_real_product_id("FLATEX_EUR"))

    def test_none_is_not_real(self):
        self.assertFalse(is_real_product_id(None))

    def test_null_literal_is_not_real(self):
        self.assertFalse(is_real_product_id("NULL"))


class SanitizeProductInfoTest(unittest.TestCase):
    def test_literal_null_string_becomes_none(self):
        info = {"isin": "NULL", "symbol": "NULL", "name": "Real Name", "closePrice": 10.0}
        self.assertEqual(sanitize_product_info(info), {
            "isin": None, "symbol": None, "name": "Real Name", "closePrice": 10.0,
        })

    def test_blank_string_becomes_none(self):
        info = {"isin": "  ", "symbol": "", "name": "Real Name", "closePrice": None}
        result = sanitize_product_info(info)
        self.assertIsNone(result["isin"])
        self.assertIsNone(result["symbol"])

    def test_null_is_case_insensitive(self):
        info = {"isin": "null", "symbol": "Null", "name": None, "closePrice": None}
        result = sanitize_product_info(info)
        self.assertIsNone(result["isin"])
        self.assertIsNone(result["symbol"])

    def test_real_values_pass_through_unchanged(self):
        info = {"isin": "IE00B4L5Y983", "symbol": "IWDA", "name": "iShares Core MSCI World", "closePrice": 38.0}
        self.assertEqual(sanitize_product_info(info), info)

    def test_missing_keys_default_to_none(self):
        result = sanitize_product_info({})
        self.assertIsNone(result["isin"])
        self.assertIsNone(result["symbol"])
        self.assertIsNone(result["name"])
        self.assertIsNone(result["closePrice"])

    def test_literal_null_close_price_becomes_none(self):
        # "NULL" is truthy, so an unsanitized closePrice would win the
        # `closePrice or price` fallback in build_positions and reach Java as a
        # non-numeric node — decoded as 0, pricing the holding at zero.
        info = {"isin": "IE00B4L5Y983", "symbol": "IWDA", "name": "iShares", "closePrice": "NULL"}
        self.assertIsNone(sanitize_product_info(info)["closePrice"])


class ValuePairsToDictTest(unittest.TestCase):
    def test_flattens_name_value_pairs(self):
        item = {"id": "1", "value": [{"name": "size", "value": 10}, {"name": "price", "value": 42.5}]}
        self.assertEqual(value_pairs_to_dict(item), {"size": 10, "price": 42.5})

    def test_falls_back_to_flat_dict(self):
        item = {"size": 10, "price": 42.5}
        self.assertEqual(value_pairs_to_dict(item), item)

    def test_ignores_non_dict_pairs(self):
        item = {"value": [{"name": "size", "value": 10}, "garbage"]}
        self.assertEqual(value_pairs_to_dict(item), {"size": 10})


class ParseCashEurTest(unittest.TestCase):
    def test_picks_eur_row(self):
        rows = [
            {"value": [{"name": "currencyCode", "value": "USD"}, {"name": "value", "value": 100.0}]},
            {"value": [{"name": "currencyCode", "value": "EUR"}, {"name": "value", "value": 250.5}]},
        ]
        self.assertEqual(parse_cash_eur(rows), 250.5)

    def test_zero_eur_balance_is_a_real_zero(self):
        rows = [{"value": [{"name": "currencyCode", "value": "EUR"}, {"name": "value", "value": 0}]}]
        self.assertEqual(parse_cash_eur(rows), 0.0)

    def test_no_eur_row_is_a_format_change_not_an_empty_account(self):
        # A French DEGIRO account always carries its EUR base-currency row; its absence
        # means DEGIRO reshaped the block. Returning 0.0 here would let Java book the
        # account at cash=0 and write that as today's snapshot.
        rows = [{"value": [{"name": "currencyCode", "value": "USD"}, {"name": "value", "value": 100.0}]}]
        with self.assertRaises(PortfolioFormatError):
            parse_cash_eur(rows)

    def test_missing_cash_funds_block_is_refused(self):
        with self.assertRaises(PortfolioFormatError):
            parse_cash_eur([])
        with self.assertRaises(PortfolioFormatError):
            parse_cash_eur(None)

    def test_eur_row_without_a_numeric_value_is_refused(self):
        for broken in (None, "", "NULL", True):
            rows = [{"value": [{"name": "currencyCode", "value": "EUR"}, {"name": "value", "value": broken}]}]
            with self.subTest(value=broken), self.assertRaises(PortfolioFormatError):
                parse_cash_eur(rows)


class ParseRawPositionsTest(unittest.TestCase):
    def test_filters_out_zero_size_positions(self):
        rows = [
            {"id": "1", "value": [{"name": "size", "value": 0}, {"name": "price", "value": 10}]},
            {"id": "2", "value": [{"name": "size", "value": 5}, {"name": "price", "value": 20}]},
        ]
        result = parse_raw_positions(rows)
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["productId"], "2")
        self.assertEqual(result[0]["size"], 5.0)
        self.assertEqual(result[0]["price"], 20.0)

    def test_id_falls_back_to_row_level_id(self):
        rows = [{"id": "12345", "value": [{"name": "size", "value": 3}, {"name": "price", "value": 7.5}]}]
        result = parse_raw_positions(rows)
        self.assertEqual(result[0]["productId"], "12345")

    def test_empty_portfolio_block_is_an_empty_list(self):
        # An empty `portfolio.value` is a legitimate (all sold) state; only a missing
        # block is a format change.
        self.assertEqual(parse_raw_positions([]), [])

    def test_missing_portfolio_block_is_refused(self):
        with self.assertRaises(PortfolioFormatError):
            parse_raw_positions(None)

    def test_real_row_without_size_is_refused_rather_than_dropped(self):
        rows = [{"id": "2", "value": [{"name": "price", "value": 20}]}]
        with self.assertRaises(PortfolioFormatError):
            parse_raw_positions(rows)

    def test_real_row_without_price_is_refused_rather_than_priced_at_zero(self):
        rows = [{"id": "2", "value": [{"name": "size", "value": 5}]}]
        with self.assertRaises(PortfolioFormatError):
            parse_raw_positions(rows)

    def test_non_numeric_size_or_price_is_refused(self):
        for field in ("size", "price"):
            rows = [{"id": "2", "value": [{"name": "size", "value": 5}, {"name": "price", "value": 20}]}]
            rows[0]["value"] = [pair if pair["name"] != field else {"name": field, "value": "NULL"}
                                for pair in rows[0]["value"]]
            with self.subTest(field=field), self.assertRaises(PortfolioFormatError):
                parse_raw_positions(rows)

    def test_pseudo_position_without_size_or_price_is_still_skipped(self):
        # The FLATEX_EUR cash row is not a priceable holding; it must be skipped
        # before its fields are required, or a cash row shaped differently from a
        # security row would fail every sync.
        rows = [
            {"id": "FLATEX_EUR", "value": [{"name": "value", "value": 250.0}]},
            {"id": "2", "value": [{"name": "size", "value": 5}, {"name": "price", "value": 20}]},
        ]
        result = parse_raw_positions(rows)
        self.assertEqual([p["productId"] for p in result], ["2"])

    def test_missing_break_even_price_falls_back_to_price(self):
        rows = [{"id": "2", "value": [{"name": "size", "value": 5}, {"name": "price", "value": 20}]}]
        self.assertEqual(parse_raw_positions(rows)[0]["breakEvenPrice"], 20.0)

    def test_flatex_cash_pseudo_position_is_excluded(self):
        rows = [
            {"id": "FLATEX_EUR", "value": [{"name": "size", "value": 250.0}, {"name": "price", "value": 1}]},
            {"id": "2", "value": [{"name": "size", "value": 5}, {"name": "price", "value": 20}]},
        ]
        result = parse_raw_positions(rows)
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["productId"], "2")


class BuildPositionsTest(unittest.TestCase):
    def test_merges_resolved_product_info_and_prefers_close_price(self):
        raw = [{"productId": "123", "size": 10.0, "price": 42.0, "breakEvenPrice": 38.0}]
        products = {"123": {
            "isin": "IE00B4L5Y983", "symbol": "IWDA", "name": "iShares Core MSCI World", "closePrice": 45.0,
        }}

        result = build_positions(raw, products)

        self.assertEqual(result, [{
            "isin": "IE00B4L5Y983",
            "symbol": "IWDA",
            "name": "iShares Core MSCI World",
            "quantity": 10.0,
            "buyingPrice": 38.0,
            "currentPrice": 45.0,
        }])

    def test_current_price_falls_back_to_portfolio_row_price_without_close_price(self):
        raw = [{"productId": "123", "size": 10.0, "price": 42.0, "breakEvenPrice": 38.0}]
        products = {"123": {"isin": "IE00B4L5Y983", "symbol": "IWDA", "name": "iShares", "closePrice": None}}

        result = build_positions(raw, products)

        self.assertEqual(result[0]["currentPrice"], 42.0)
        self.assertEqual(result[0]["buyingPrice"], 38.0)

    def test_literal_null_close_price_falls_back_to_portfolio_row_price(self):
        # End-to-end over the sanitize step: a "NULL" closePrice must not be treated
        # as a real reference price, otherwise the holding is priced at zero and the
        # account balance is silently understated.
        raw = [{"productId": "123", "size": 10.0, "price": 42.0, "breakEvenPrice": 38.0}]
        products = build_product_info_map({"123": {
            "isin": "IE00B4L5Y983", "symbol": "IWDA", "name": "iShares", "closePrice": "NULL",
        }})

        result = build_positions(raw, products)

        self.assertEqual(result[0]["currentPrice"], 42.0)

    def test_survives_missing_product_info_with_raw_id_labels(self):
        raw = [{"productId": "999", "size": 1.0, "price": 5.0, "breakEvenPrice": 4.5}]

        result = build_positions(raw, {})

        self.assertEqual(result[0]["isin"], None)
        self.assertEqual(result[0]["symbol"], "999")
        self.assertEqual(result[0]["name"], "999")
        self.assertEqual(result[0]["buyingPrice"], 4.5)
        self.assertEqual(result[0]["currentPrice"], 5.0)

    def test_none_product_id_does_not_crash(self):
        raw = [{"productId": None, "size": 1.0, "price": 5.0, "breakEvenPrice": 5.0}]

        result = build_positions(raw, {})

        self.assertEqual(result[0]["symbol"], "None")


class DescribeProductInfoTest(unittest.TestCase):
    def test_describes_shape_without_any_value(self):
        data = {
            "15690087": {"isin": "IE00BGSF1X88", "symbol": "IB01", "name": "iShares Treasury", "closePrice": 121.36},
            "65147": {"isin": "FR0000131104", "symbol": "BNP", "name": "BNP Paribas SA", "closePrice": 111.88},
        }

        described = describe_product_info(data)

        self.assertEqual(described, "products=2; fields=['closePrice', 'isin', 'name', 'symbol']")
        for secret in ("IE00BGSF1X88", "IB01", "BNP Paribas", "121.36"):
            self.assertNotIn(secret, described)

    def test_non_object_payload_is_described_by_type(self):
        self.assertEqual(describe_product_info([]), "type=list")


class BuildProductInfoMapTest(unittest.TestCase):
    def test_keys_stay_strings_matching_json_object_keys(self):
        data = {"15690087": {"isin": "IE00BGSF1X88", "symbol": "IB01", "name": "iShares Treasury", "closePrice": 121.36}}

        result = build_product_info_map(data)

        self.assertIn("15690087", result)
        self.assertNotIn(15690087, result)

    def test_skips_non_numeric_pseudo_position_ids(self):
        data = {
            "FLATEX_EUR": {"isin": None, "symbol": None, "name": "Cash"},
            "65147": {"isin": "FR0000131104", "symbol": "BNP", "name": "BNP Paribas SA", "closePrice": 111.88},
        }

        result = build_product_info_map(data)

        self.assertNotIn("FLATEX_EUR", result)
        self.assertIn("65147", result)

    def test_empty_or_none_data_returns_empty_map(self):
        self.assertEqual(build_product_info_map({}), {})
        self.assertEqual(build_product_info_map(None), {})

    def test_end_to_end_enrichment_actually_matches(self):
        """Regression test for the real bug found live: _fetch_product_info used to key
        `products` by int(pid) while build_positions looks positions up by the string
        productId parse_raw_positions produces — enrichment silently never matched,
        even though the raw DEGIRO response (reproduced here) was perfectly clean."""
        portfolio_rows = [{
            "id": "15690087",
            "value": [{"name": "size", "value": 290.67}, {"name": "price", "value": 121.36},
                      {"name": "breakEvenPrice", "value": 69.48}],
        }]
        product_info_response = {"15690087": {
            "isin": "IE00BGSF1X88", "symbol": "IB01",
            "name": "iShares $ Treasury Bond 0-1yr UCITS ETF USD A", "closePrice": 121.36,
        }}

        raw_positions = parse_raw_positions(portfolio_rows)
        products = build_product_info_map(product_info_response)
        result = build_positions(raw_positions, products)

        self.assertEqual(result, [{
            "isin": "IE00BGSF1X88",
            "symbol": "IB01",
            "name": "iShares $ Treasury Bond 0-1yr UCITS ETF USD A",
            "quantity": 290.67,
            "buyingPrice": 69.48,
            "currentPrice": 121.36,
        }])


if __name__ == "__main__":
    unittest.main()
