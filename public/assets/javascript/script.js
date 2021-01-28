var expenseCheckCounter = 0;
var incomeCheckCounter = 0;
expenseCheckArr = [];

var saveExpenses = function (newDataObject) {
    var currentExpenses = JSON.parse(localStorage.getItem('expenseItems')) || [];
    currentExpenses.push(newDataObject);
    localStorage.setItem('expenseItems', JSON.stringify(currentExpenses));
}

var saveIncome = function (newDataObject2) {
    var currentIncome = JSON.parse(localStorage.getItem('incomeItems')) || [];
    currentIncome.push(newDataObject2);
    localStorage.setItem('incomeItems', JSON.stringify(currentIncome));
}

var getExpenseValHandler = function () {
    //get the values of the form
    var expenseName = $('#expenseName').val().trim();
    var expenseAmount = $('#expenseAmount').val().trim();

    var expenseItems = {
        name: expenseName,
        amount: expenseAmount
    }

    if (!expenseName) {
        alert("Please fill out all the feilds!")
        window.location.reload()
    } else {
        saveExpenses(expenseItems)
    }
    expenseCheckCounter++
    printExpenses(expenseCheckCounter)

    $('#expenseName').val("")
    $('#expenseAmount').val("")


    expenseTotal()

};

var getIncomeValHandler = function () {
    var incomeName = $('#incomeName').val().trim();
    var incomeAmount = $('#incomeAmount').val().trim();

    var incomeItems = {
        name: incomeName,
        amount: incomeAmount
    };

    if (!incomeName) {
        alert("Please fill out all fields!")
        window.location.reload()
    } else {
        saveIncome(incomeItems)
    }

    incomeCheckCounter++
    printIncomes(incomeCheckCounter)

    $('#incomeName').val("")
    $('#incomeAmount').val("")

    incomeTotal();
}
var printExpenses = function (expenseCheckCounter) {
    var currentExpenses = JSON.parse(localStorage.getItem('expenseItems')) || [];

    $('.expense-output-wrapper').empty();

    currentExpenses.forEach(function (element, i) {
        var expenseItems = $("<div>");
        expenseItems.addClass("expense-items");

        //create slash button
        var slashButton = $('<input>');
        slashButton.addClass("checkbox");
        slashButton.attr("id", i)
        slashButton.attr("type", "checkbox");

        // create p tags
        var expenseNameText = $('<p>');
        var expenseAmountText = $('<p>');

        expenseNameText.addClass("expenseNameP");
        expenseAmountText.addClass("expenseAmountP");

        expenseNameText.text(element.name);
        expenseAmountText.text(element.amount);
        expenseNameText.prepend(slashButton);
        expenseItems.append(expenseNameText);
        expenseItems.append(expenseAmountText);
        $('.expense-output-wrapper').append(expenseItems);

        // var checkbox = $('.checkbox')
        // if (checkbox.prop("checked") == true) {
        //     localStorage.setItem('checkbox', true)
        // } else if (checkbox.prop("checked") == false) {
        //     localStorage.setItem("checkbox", false)
        // };

        // var checked = JSON.parse(localStorage.getItem('checkbox'))
        // if (checked == true) {
        //     document.querySelector('.checkbox').checked = checked;
        // };
    });

};
$(".expenseNameP").on("click", ".checkbox", function () {
    var checkbox = $(this)
    if (checkbox.prop("checked") == true) {
        check = true;
    } else if (checkbox.prop("checked") == false) {
        check = false;
    };
    var expenseCheckboxIndex = $(this).attr("id").replace();
    var expenseCheckObj = {
        checkIndex: expenseCheckboxIndex,
        checkStatus: check
    };
    console.log("hello")
    saveExpenseCheckbox(expenseCheckObj);
});

var saveExpenseCheckbox = function (expenseCheckObj) {
    expenseCheckArr.push(expenseCheckObj);
    localStorage.setItem('checkStatus', JSON.stringify(expenseCheckArr));
};

printExpenses();

$(".total-expense").find("span").text("0")

var printIncomes = function (incomeCheckCounter) {
    var currentIncome = JSON.parse(localStorage.getItem('incomeItems')) || [];

    $('.income-output-wrapper').empty();
    currentIncome.forEach(element => {
        var incomeItems = $("<div>");
        incomeItems.addClass("income-items");

        var slashButton = $("<input>");
        slashButton.attr("type", "checkbox");
        slashButton.attr("onclick", "saveCheckbox()")
        slashButton.attr("data-incomeSlashButton", incomeCheckCounter)
        slashButton.addClass("checkbox");

        var incomeNameText = $('<p>');
        var incomeAmountText = $('<p>');

        incomeNameText.addClass("incomeNameP");
        incomeAmountText.addClass("incomeAmountP");

        incomeNameText.text(element.name);
        incomeAmountText.text(element.amount);
        incomeNameText.prepend(slashButton)
        incomeItems.append(incomeNameText);
        incomeItems.append(incomeAmountText);
        $('.income-output-wrapper').append(incomeItems);
    });
};
printIncomes();
$(".total-income").find("span").text("0");


var leftOver = function () {
    var totalAmount = incomeCounter - expenseCounter;
    console.log(totalAmount)

    $(".left-over").find("span").text(totalAmount)
}

var expenseCounter = 0;
var expenseTotal = function () {

    var expenseAmountArrObj = JSON.parse(localStorage.getItem('expenseItems')) || [];
    for (var i = 0; i < expenseAmountArrObj.length; i++) {
        expenseCounter = parseInt(expenseAmountArrObj[i].amount) + expenseCounter;
    }
    $(".total-expense").find("span").text(expenseCounter)

}
expenseTotal()
var incomeCounter = 0;
var incomeTotal = function () {

    var incomeAmountArrObj = JSON.parse(localStorage.getItem('incomeItems')) || [];
    for (var i = 0; i < incomeAmountArrObj.length; i++) {
        incomeCounter = parseInt(incomeAmountArrObj[i].amount) + incomeCounter;
    }
    $('.total-income').find('span').text(incomeCounter);
}
incomeTotal();
leftOver()



$('.add-expense').on('click', getExpenseValHandler)
$('.add-income').on('click', getIncomeValHandler)

