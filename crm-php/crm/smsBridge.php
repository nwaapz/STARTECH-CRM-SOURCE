<?php


// Retrieve the incoming raw POST data
$rawData = file_get_contents('php://input');

// Log the raw data for debugging purposes
error_log("Raw Data Received: " . $rawData);

// Decode the JSON into an associative array
$data = json_decode($rawData, true);

// Check if the phone number exists in the decoded data
if (isset($data['phoneNumber'])) {
    $phoneNumber = $data['phoneNumber'];

    // Log the decoded phone number for debugging
    error_log("Phone Number: " . $phoneNumber);

    // Send message (mock logic here)
    
    SendMessage($phoneNumber, "ثبت انجام شد");
    // Mock success response
    echo json_encode(["success" => true, "message" => "SMS sent to $phoneNumber"]);
} else {
    // Log missing phone number error
    error_log("Phone number is missing in the data");

    echo json_encode(["success" => false, "message" => "Phone number is missing"]);
}



function SendMessage($phone, $message)
{
    ini_set("soap.wsdl_cache_enabled", 0);
    $sms = new SoapClient("http://api.payamak-panel.com/post/Send.asmx?wsdl", [
        "encoding" => "UTF-8",
    ]);
    $data = [
        "username" => "09121777039",
        "password" => "4thvahdati@FB",
        "to" => [$phone],
        "from" => "20001390",
        "text" => "-" . $message . "-",
        "isflash" => false,
    ];
    $result = $sms->SendSimpleSMS($data)->SendSimpleSMSResult;
}
?>
