<%@ page language="java" contentType="text/html; charset=UTF-8"
    pageEncoding="UTF-8"%>
<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" "http://www.w3.org/TR/html4/loose.dtd">
<html>
<head>
<%@include file="/template/includes.jsp" %>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
<title><fmt:message key="div.SpecifyParameters" /></title>

  <!-- Load in Select2 files so can create fancy route selector -->
  <link href="//cdnjs.cloudflare.com/ajax/libs/select2/4.0.0/css/select2.min.css" rel="stylesheet" />
  <script src="//cdnjs.cloudflare.com/ajax/libs/select2/4.0.0/js/select2.min.js"></script>
  
  <link href="../params/reportParams.css" rel="stylesheet"/>

  <script>
    function execute() {
      var selectedRouteId = $("#route").val();
      var stopId = $("#stopId").val();
      var numPreds = $("#numPreds").val();
      var format = $('input:radio[name=format]:checked').val();
  	  var url = apiUrlPrefix + "/command/predictions?rs=" + selectedRouteId + "|" + stopId
		  	  + (numPreds!=""?"&numPreds=" + numPreds:"")
  			  + "&format=" + format;

   	  // Actually do the API call
   	  location.href = url;
    }
  </script>
  
</head>
<body>

<%@include file="/template/header.jsp" %>

<div id="title">
   <fmt:message key="div.spfpbrsa" />
</div>
   
<div id="mainDiv">   
   <%-- Create route selector --%>
   <jsp:include page="../params/routeSingle.jsp" />
   
   <div class="param">
    <label for="stop"><fmt:message key="div.stopid" /></label>
    <input type="text" id="stopId" size="10" />
   </div>
   
   <div class="param">
    <label for="numPreds"><fmt:message key="div.np" /></label>
    <input type="text" id="numPreds" size="10" /> <span class="note"><fmt:message key="div.difps" /></span>
   </div>
   
   <%-- Create json/xml format radio buttons --%>
   <jsp:include page="../params/jsonXmlFormat.jsp" />
   
   <%-- Create submit button --%> 
   <jsp:include page="../params/submitApiCall.jsp" />    
</div>

</body>
</html>